/*
 * Copyright (c) 2008-2014 MongoDB Inc. <http://10gen.com>
 *
 * Licensed under the Apache License, Version 2.0 (the "License")
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.mongodb

import org.bson.types.ObjectId
import spock.lang.Unroll

import static com.mongodb.Fixture.isReplicaSet
import static com.mongodb.Fixture.isServerStartedWithJournalingDisabled
import static com.mongodb.Fixture.isSharded
import static com.mongodb.Fixture.isStandalone
import static com.mongodb.Fixture.serverIsAtLeastVersion
import static com.mongodb.WriteRequest.Type.INSERT
import static com.mongodb.WriteRequest.Type.REMOVE
import static com.mongodb.WriteRequest.Type.REPLACE
import static com.mongodb.WriteRequest.Type.UPDATE
import static org.junit.Assume.assumeTrue

@Unroll
class BulkWriteOperationSpecification extends FunctionalSpecification {

    def 'when no document with the same id exists, should insert the document'() {
        given:
        def operation = initializeBulkOperation(ordered)
        operation.insert(new BasicDBObject('_id', 1))

        when:
        def result = operation.execute()

        then:
        result == new AcknowledgedBulkWriteResult(INSERT, 1, [])
        collection.findOne() == new BasicDBObject('_id', 1)

        where:
        ordered << [true, false]
    }

    def 'when a document contains a key with an illegal character, inserting it should throw IllegalArgumentException'() {
        given:
        def operation = initializeBulkOperation(ordered)
        operation.insert(new BasicDBObject('$set', 1))

        when:
        operation.execute()

        then:
        thrown(IllegalArgumentException)

        where:
        ordered << [true, false]
    }

    def 'when a document with the same id exists, should throw an exception'() {
        given:
        def document = new BasicDBObject('_id', 1)
        collection.insert(document)
        def operation = initializeBulkOperation(ordered)
        operation.insert(document)

        when:
        operation.execute()

        then:
        def ex = thrown(BulkWriteException)
        ex.getWriteErrors().get(0).code == 11000

        where:
        ordered << [true, false]
    }

    def 'when a document with no _id  is inserted, the _id should be generated by the driver'() {
        given:
        def operation = initializeBulkOperation(ordered)
        def document = new BasicDBObject()
        operation.insert(document)

        when:
        def result = operation.execute()

        then:
        result == new AcknowledgedBulkWriteResult(INSERT, 1, [])
        document._id instanceof ObjectId
        collection.findOne() == document

        where:
        ordered << [true, false]
    }

    def 'when documents match the query, a remove of one should remove one of them'() {
        given:
        collection.insert(new BasicDBObject('x', true))
        collection.insert(new BasicDBObject('x', true))
        def operation = initializeBulkOperation(ordered)
        operation.find(new BasicDBObject('x', true)).removeOne()

        when:
        def result = operation.execute()

        then:
        result == new AcknowledgedBulkWriteResult(REMOVE, 1, [])
        collection.count() == 1

        where:
        ordered << [true, false]
    }

    def 'when documents match the query, a remove should remove all of them'() {
        given:
        collection.insert(new BasicDBObject('x', true))
        collection.insert(new BasicDBObject('x', true))
        collection.insert(new BasicDBObject('x', false))
        def operation = initializeBulkOperation(ordered)
        operation.find(new BasicDBObject('x', true)).remove()

        when:
        def result = operation.execute()

        then:
        result == new AcknowledgedBulkWriteResult(REMOVE, 2, [])
        collection.count(new BasicDBObject('x', false)) == 1

        where:
        ordered << [true, false]
    }

    def 'when an update document contains a non $-prefixed key, update should throw IllegalArgumentException'() {
        given:
        def operation = initializeBulkOperation(ordered)
        operation.find(new BasicDBObject()).update(new BasicDBObject('$set', new BasicDBObject('x', 1)).append('y', 2))

        when:
        operation.execute()

        then:
        thrown(IllegalArgumentException)

        where:
        ordered << [true, false]
    }

    def 'when an update document contains a non $-prefixed key, updateOne should throw IllegalArgumentException'() {
        given:
        def operation = initializeBulkOperation(ordered)
        operation.find(new BasicDBObject()).updateOne(new BasicDBObject('$set', new BasicDBObject('x', 1)).append('y', 2))

        when:
        operation.execute()

        then:
        thrown(IllegalArgumentException)

        where:
        ordered << [true, false]
    }

    def 'when multiple document match the query, updateOne should update only one of them'() {
        given:
        collection.insert(new BasicDBObject('x', true))
        collection.insert(new BasicDBObject('x', true))

        def operation = initializeBulkOperation(ordered)
        operation.find(new BasicDBObject('x', true)).updateOne(new BasicDBObject('$set', new BasicDBObject('y', 1)))

        when:
        def result = operation.execute()

        then:
        result == new AcknowledgedBulkWriteResult(UPDATE, 1, expectedModifiedCount(1), [])
        collection.find(new BasicDBObject('y', 1), new BasicDBObject('x', 1).append('_id', 0)).toArray() == [new BasicDBObject('x', true)]

        where:
        ordered << [true, false]
    }

     def 'when documents match the query, an update should update all of them'() {
        given:
        collection.insert(new BasicDBObject('x', true))
        collection.insert(new BasicDBObject('x', true))
        collection.insert(new BasicDBObject('x', false))

        def operation = initializeBulkOperation(ordered)
        operation.find(new BasicDBObject('x', true)).update(new BasicDBObject('$set', new BasicDBObject('y', 1)))

        when:
        def result = operation.execute()

        then:
        result == new AcknowledgedBulkWriteResult(UPDATE, 2, expectedModifiedCount(2), [])
        collection.count(new BasicDBObject('y', 1)) == 2

        where:
        ordered << [true, false]
    }

    def 'when no document matches the query, updateOne with upsert should insert a document'() {
        given:
        def id = new ObjectId()
        def operation = initializeBulkOperation(ordered)
        operation.find(new BasicDBObject('_id', id)).upsert().updateOne(new BasicDBObject('$set', new BasicDBObject('x', 2)))

        when:
        def result = operation.execute()

        then:
        result == new AcknowledgedBulkWriteResult(UPDATE, 0, expectedModifiedCount(0), [new BulkWriteUpsert(0, id)])
        collection.findOne() == new BasicDBObject('_id', id).append('x', 2)

        where:
        ordered << [true, false]
    }

    def 'when no document matches the query, update with upsert should insert a document'() {
        given:
        def id = new ObjectId()
        def operation = initializeBulkOperation(ordered)
        operation.find(new BasicDBObject('_id', id)).upsert().update(new BasicDBObject('$set', new BasicDBObject('x', 2)))

        when:
        def result = operation.execute()

        then:
        result == new AcknowledgedBulkWriteResult(UPDATE, 0, expectedModifiedCount(0), [new BulkWriteUpsert(0, id)])
        collection.findOne() == new BasicDBObject('_id', id).append('x', 2)

        where:
        ordered << [true, false]
    }

    def 'when documents matches the query, update with upsert should update all of them'() {
        given:
        collection.insert(new BasicDBObject('x', true))
        collection.insert(new BasicDBObject('x', true))
        collection.insert(new BasicDBObject('x', false))

        def operation = initializeBulkOperation(ordered)
        operation.find(new BasicDBObject('x', true)).upsert().update(new BasicDBObject('$set', new BasicDBObject('y', 1)))

        when:
        def result = operation.execute()

        then:
        result == new AcknowledgedBulkWriteResult(UPDATE, 2, expectedModifiedCount(2), [])
        collection.count(new BasicDBObject('y', 1)) == 2

        where:
        ordered << [true, false]
    }

    def 'when a document contains a key with an illegal character, replacing a document with it should throw IllegalArgumentException'() {
        given:
        def id = new ObjectId()
        def operation = initializeBulkOperation(ordered)
        def query = new BasicDBObject('_id', id)
        operation.find(query).upsert().replaceOne(new BasicDBObject('$set', new BasicDBObject('x', 1)))

        when:
        operation.execute()

        then:
        thrown(IllegalArgumentException)

        where:
        ordered << [true, false]
    }

    def 'when no document matches the query, a replace with upsert should insert a document'() {
        given:
        def id = new ObjectId()
        def operation = initializeBulkOperation(ordered)
        def query = new BasicDBObject('_id', id)
        operation.find(query).upsert().replaceOne(new BasicDBObject('_id', id).append('x', 2))

        when:
        def result = operation.execute()

        then:
        result == new AcknowledgedBulkWriteResult(UPDATE, 0, expectedModifiedCount(0), [new BulkWriteUpsert(0, id)])
        collection.findOne() == new BasicDBObject('_id', id).append('x', 2)

        where:
        ordered << [true, false]
    }

    def 'when multiple documents match the query, replaceOne should replace one of them'() {
        given:
        collection.insert(new BasicDBObject('x', true))
        collection.insert(new BasicDBObject('x', true))

        def operation = initializeBulkOperation(ordered)
        def replacement = new BasicDBObject('y', 1).append('x', false)
        operation.find(new BasicDBObject('x', true)).replaceOne(replacement)

        when:
        def result = operation.execute()

        then:
        result == new AcknowledgedBulkWriteResult(UPDATE, 1, expectedModifiedCount(1), [])
        collection.find(new BasicDBObject('x', false), new BasicDBObject('_id', 0)).toArray() == [replacement]

        where:
        ordered << [true, false]
    }

    def 'when a document matches the query, updateOne with upsert should update that document'() {
        given:
        def id = new ObjectId()
        collection.insert(new BasicDBObject('_id', id))
        def operation = initializeBulkOperation(ordered)
        operation.find(new BasicDBObject('_id', id)).upsert().updateOne(new BasicDBObject('$set', new BasicDBObject('x', 2)))

        when:
        def result = operation.execute()

        then:
        result == new AcknowledgedBulkWriteResult(UPDATE, 1, expectedModifiedCount(1), [])
        collection.findOne() == new BasicDBObject('_id', id).append('x', 2)

        where:
        ordered << [true, false]
    }

    def 'when a document matches the query, a replace with upsert should update that document'() {
        given:
        collection.insert(new BasicDBObject('_id', 1))

        def operation = initializeBulkOperation(ordered)
        operation.find(new BasicDBObject('_id', 1)).upsert().replaceOne(new BasicDBObject('_id', 1).append('x', 2))

        when:
        def result = operation.execute()

        then:
        result == new AcknowledgedBulkWriteResult(REPLACE, 1, expectedModifiedCount(1), [])
        collection.findOne() == new BasicDBObject('_id', 1).append('x', 2)

        where:
        ordered << [true, false]
    }

    def 'when a custom _id is upserted it should be in the write result'() {
        given:
        def operation = initializeBulkOperation(ordered)
        operation.find(new BasicDBObject('_id', 0)).upsert()
                .updateOne(new BasicDBObject('$set', new BasicDBObject('a', 0)))
        operation.find(new BasicDBObject('a', 1)).upsert().replaceOne(new BasicDBObject('_id', 1))
        operation.find(new BasicDBObject('_id', 2)).upsert().replaceOne(new BasicDBObject('_id', 2))

        when:
        def result = operation.execute()

        then:
        result == new AcknowledgedBulkWriteResult(UPDATE, 0, expectedModifiedCount(0), [new BulkWriteUpsert(0, 0),
                                                                                        new BulkWriteUpsert(1, 1),
                                                                                        new BulkWriteUpsert(2, 2)])
        collection.count() == 3

        where:
        ordered << [true, false]
    }

    def 'unacknowledged upserts with custom _id should not error'() {
        given:
        collection.getDB().requestStart()
        def operation = initializeBulkOperation(ordered)
        operation.find(new BasicDBObject('_id', 0)).upsert()
                .updateOne(new BasicDBObject('$set', new BasicDBObject('a', 0)))
        operation.find(new BasicDBObject('a', 1)).upsert().replaceOne(new BasicDBObject('_id', 1))
        operation.find(new BasicDBObject('_id', 2)).upsert().replaceOne(new BasicDBObject('_id', 2))

        when:
        def result = operation.execute(WriteConcern.UNACKNOWLEDGED)
        collection.insert(new BasicDBObject('_id', 4))

        then:
        result == new UnacknowledgedBulkWriteResult()
        collection.count() == 4

        cleanup:
        collection.getDB().requestDone()

        where:
        ordered << [true, false]
    }

    def 'when a replacement document is 16MB, the document is still replaced'() {
        given:
        collection.insert(new BasicDBObject('_id', 1))

        def operation = collection.initializeOrderedBulkOperation()
        operation.find(new BasicDBObject('_id', 1)).upsert().replaceOne(new BasicDBObject('_id', 1)
                                                                                .append('x', new byte[1024 * 1024 * 16 - 30]))

        when:
        def result = operation.execute()

        then:
        result == new AcknowledgedBulkWriteResult(REPLACE, 1, expectedModifiedCount(1), [])
        collection.count() == 1
    }

    def 'when two update documents together exceed 16MB, the documents are still updated'() {
        given:
        def operation = collection.initializeOrderedBulkOperation()
        operation.find(new BasicDBObject('_id', 1))
                 .update(new BasicDBObject('$set', new BasicDBObject('x', new byte[1024 * 1024 * 8])));
        operation.find(new BasicDBObject('_id', 2))
                 .update(new BasicDBObject('$set', new BasicDBObject('x', new byte[1024 * 1024 * 8])));

        when:
        def result = operation.execute()

        then:
        result == new AcknowledgedBulkWriteResult(UPDATE, 0, expectedModifiedCount(0), [])
        collection.count() == 0
    }


    def 'should handle multi-length runs of ordered insert, update, replace, and remove'() {
        given:
        collection.insert(getTestInserts())

        def operation = collection.initializeOrderedBulkOperation()
        addWritesToOperation(operation)

        when:
        def result = operation.execute()

        then:
        result == new AcknowledgedBulkWriteResult(2, 4, 2, expectedModifiedCount(4), [])

        collection.findOne(new BasicDBObject('_id', 1)) == new BasicDBObject('_id', 1).append('x', 2)
        collection.findOne(new BasicDBObject('_id', 2)) == new BasicDBObject('_id', 2).append('x', 3)
        collection.findOne(new BasicDBObject('_id', 3)) == null
        collection.findOne(new BasicDBObject('_id', 4)) == null
        collection.findOne(new BasicDBObject('_id', 5)) == new BasicDBObject('_id', 5).append('x', 4)
        collection.findOne(new BasicDBObject('_id', 6)) == new BasicDBObject('_id', 6).append('x', 5)
        collection.findOne(new BasicDBObject('_id', 7)) == new BasicDBObject('_id', 7)
        collection.findOne(new BasicDBObject('_id', 8)) == new BasicDBObject('_id', 8)
    }

    def 'should handle multi-length runs of unacknowledged insert, update, replace, and remove'() {
        given:
        collection.getDB().requestStart()
        collection.insert(getTestInserts())

        def operation = initializeBulkOperation(ordered)
        addWritesToOperation(operation)

        when:
        def result = operation.execute(WriteConcern.UNACKNOWLEDGED)
        collection.insert(new BasicDBObject('_id', 9))

        then:
        !result.isAcknowledged()
        collection.findOne(new BasicDBObject('_id', 1)) == new BasicDBObject('_id', 1).append('x', 2)
        collection.findOne(new BasicDBObject('_id', 2)) == new BasicDBObject('_id', 2).append('x', 3)
        collection.findOne(new BasicDBObject('_id', 3)) == null
        collection.findOne(new BasicDBObject('_id', 4)) == null
        collection.findOne(new BasicDBObject('_id', 5)) == new BasicDBObject('_id', 5).append('x', 4)
        collection.findOne(new BasicDBObject('_id', 6)) == new BasicDBObject('_id', 6).append('x', 5)
        collection.findOne(new BasicDBObject('_id', 7)) == new BasicDBObject('_id', 7)
        collection.findOne(new BasicDBObject('_id', 8)) == new BasicDBObject('_id', 8)

        cleanup:
        collection.getDB().requestDone()

        where:
        ordered << [true, false]
    }

    def 'error details should have correct index on ordered write failure'() {
        given:
        def operation = initializeBulkOperation(ordered)
        operation.insert(new BasicDBObject('_id', 1))
        operation.find(new BasicDBObject('_id', 1)).updateOne(new BasicDBObject('$set', new BasicDBObject('x', 3)))
        operation.insert(new BasicDBObject('_id', 1))

        when:
        operation.execute()

        then:
        def ex = thrown(BulkWriteException)
        ex.writeErrors.size() == 1
        ex.writeErrors[0].index == 2
        ex.writeErrors[0].code == 11000

        where:
        ordered << [true, false]
    }

    def 'should handle multi-length runs of unordered insert, update, replace, and remove'() {
        given:
        collection.insert(getTestInserts())
        def operation = collection.initializeUnorderedBulkOperation()
        addWritesToOperation(operation)

        when:
        def result = operation.execute()

        then:
        result == new AcknowledgedBulkWriteResult(2, 4, 2, expectedModifiedCount(4), [])

        collection.findOne(new BasicDBObject('_id', 1)) == new BasicDBObject('_id', 1).append('x', 2)
        collection.findOne(new BasicDBObject('_id', 2)) == new BasicDBObject('_id', 2).append('x', 3)
        collection.findOne(new BasicDBObject('_id', 3)) == null
        collection.findOne(new BasicDBObject('_id', 4)) == null
        collection.findOne(new BasicDBObject('_id', 5)) == new BasicDBObject('_id', 5).append('x', 4)
        collection.findOne(new BasicDBObject('_id', 6)) == new BasicDBObject('_id', 6).append('x', 5)
        collection.findOne(new BasicDBObject('_id', 7)) == new BasicDBObject('_id', 7)
        collection.findOne(new BasicDBObject('_id', 8)) == new BasicDBObject('_id', 8)
    }

    def 'should split when the number of writes is larger than the match write batch size'() {
        given:
        def operation = initializeBulkOperation(ordered)
        (0..2000).each {
            operation.insert(new BasicDBObject())
        }

        when:
        operation.execute()

        then:
        collection.find().count() == 2001

        where:
        ordered << [true, false]
    }

    def 'should split when the message size would exceed the max command message size'() {
        given:
        def operation = collection.initializeUnorderedBulkOperation()
        (0..5).each {
            operation.insert(new BasicDBObject('binary', new byte[1024 * 1024 * 4]))
        }

        when:
        operation.execute()

        then:
        collection.count() == 6
    }

    def 'should throw correct BulkWriteException when the message size would exceed the max command message size'(boolean ordered) {
        given:
        def operation = initializeBulkOperation(ordered)
        (0..5).each {
            operation.insert(new BasicDBObject('_id', it).append('binary', new byte[1024 * 1024 * 4]))
        }
        operation.insert(new BasicDBObject('_id', 0))  // duplicate key
        operation.insert(new BasicDBObject('_id', 6))

        when:
        operation.execute()

        then:
        def ex = thrown(BulkWriteException)
        ex.writeErrors.size() == 1
        ex.writeErrors[0].index == 6
        ex.writeResult == new AcknowledgedBulkWriteResult(INSERT, ordered ? 6 : 7, []) // for ordered,  last doc will not be inserted

        where:
        ordered << [true, false]
    }

    def 'should insert all documents when the number of inserts is larger than the match write batch size '(boolean ordered) {
        given:
        def operation = initializeBulkOperation(ordered)
        (0..1001).each {
            operation.insert(new BasicDBObject('_id', it))
        }

        when:
        operation.execute()

        then:
        collection.count == 1002

        where:
        ordered << [true, false]
    }

    def 'should throw correct BulkWriteException when the number of writes is larger than the match write batch size '(boolean ordered) {
        given:
        def operation = initializeBulkOperation(ordered)
        (0..999).each {
            operation.insert(new BasicDBObject('_id', it))
        }

        operation.insert(new BasicDBObject('_id', 0))  // duplicate key
        operation.insert(new BasicDBObject('_id', 1000))

        when:
        operation.execute()

        then:
        def ex = thrown(BulkWriteException)
        ex.writeErrors.size() == 1
        ex.writeErrors[0].index == 1000
        ex.writeResult == new AcknowledgedBulkWriteResult(INSERT, ordered ? 1000 : 1001, []) // for ordered, last doc will not be inserted

        where:
        ordered << [true, false]
    }

    def 'error details should have correct index on unordered write failure'() {
        given:
        collection.insert(getTestInserts())

        def operation = collection.initializeUnorderedBulkOperation()
        operation.insert(new BasicDBObject('_id', 1))
        operation.find(new BasicDBObject('_id', 2)).updateOne(new BasicDBObject('$set', new BasicDBObject('x', 3)))
        operation.insert(new BasicDBObject('_id', 3))

        when:
        operation.execute()

        then:
        def ex = thrown(BulkWriteException)
        ex.writeErrors.size() == 2
        ex.writeErrors[0].index == 0
        ex.writeErrors[0].code == 11000
        ex.writeErrors[1].index == 2
        ex.writeErrors[1].code == 11000
    }

    def 'when there is a duplicate key error and a write concern error, both should be reported'() {
        assumeTrue(isReplicaSet())

        given:
        collection.insert(getTestInserts())

        def operation = initializeBulkOperation(ordered)
        operation.insert(new BasicDBObject('_id', 7))
        operation.insert(new BasicDBObject('_id', 1)) // duplicate key

        when:
        operation.execute(new WriteConcern(4, 1))  // This is assuming that it won't be able to replicate to 4 servers in 1 ms

        then:
        def ex = thrown(BulkWriteException)
        ex.writeErrors.size() == 1
        ex.writeErrors[0].index == 1
        ex.writeErrors[0].code == 11000
        ex.writeConcernError != null

        where:
        ordered << [false]
    }

    def 'when w > 1 write concern is used on a standalone server with write commands, CommandFailureException is thrown'() {
        assumeTrue(isStandalone() && serverIsAtLeastVersion(2.6))

        given:
        def operation = collection.initializeUnorderedBulkOperation()
        operation.insert(new BasicDBObject('_id', 1))

        when:
        operation.execute(new WriteConcern(2, 1))

        then:
        thrown(CommandFailureException)

        where:
        ordered << [true, false]
    }

    def 'when a replication timeout occurs, an exception is thrown'() {
        assumeTrue(isReplicaSet())

        given:
        def operation = collection.initializeUnorderedBulkOperation()
        operation.insert(new BasicDBObject('_id', 1))

        when:
        operation.execute(new WriteConcern(5, 1))

        then:
        thrown(BulkWriteException)

        where:
        ordered << [true, false]
    }

    def 'when j write concern is used on a server without journaling or write commands, BulkWriteException is thrown'() {
        assumeTrue(!isSharded() && isServerStartedWithJournalingDisabled() && !serverIsAtLeastVersion(2.6))

        given:
        def operation = collection.initializeUnorderedBulkOperation()
        operation.insert(new BasicDBObject('_id', 1))
        operation.insert(new BasicDBObject('_id', 2))

        when:
        operation.execute(WriteConcern.JOURNALED)

        then:
        def e = thrown(BulkWriteException)
        e.writeResult == new AcknowledgedBulkWriteResult(INSERT, 2, [])
        e.writeConcernError != null
        e.writeConcernError.getDetails().containsField('jnote')

        where:
        ordered << [true, false]
    }

    def 'execute should throw IllegalStateException when already executed'() {
        given:
        def operation = initializeBulkOperation(ordered)
        operation.insert(new BasicDBObject('_id', 1))
        operation.execute()

        when:
        operation.execute()

        then:
        thrown(IllegalStateException)

        where:
        ordered << [true, false]
    }

    def 'execute with write concern should throw IllegalStateException when already executed'() {
        given:
        def operation = initializeBulkOperation(ordered)
        operation.insert(new BasicDBObject('_id', 1))
        operation.execute()

        when:
        operation.execute(WriteConcern.ACKNOWLEDGED)

        then:
        thrown(IllegalStateException)

        where:
        ordered << [true, false]
    }

    def 'insert should throw IllegalStateException when already executed'() {
        given:
        def operation = initializeBulkOperation(ordered)
        operation.insert(new BasicDBObject('_id', 1))
        operation.execute()

        when:
        operation.insert(new BasicDBObject())

        then:
        thrown(IllegalStateException)

        where:
        ordered << [true, false]
    }

    def 'find should throw IllegalStateException when already executed'() {
        given:
        def operation = initializeBulkOperation(ordered)
        operation.insert(new BasicDBObject('_id', 1))
        operation.execute()

        when:
        operation.find(new BasicDBObject())

        then:
        thrown(IllegalStateException)

        where:
        ordered << [true, false]
    }


    // just need to check one case here, since the others are checked above
    def 'should throw IllegalStateException when already executed with write concern'() {
        given:
        def operation = initializeBulkOperation(ordered)
        operation.insert(new BasicDBObject('_id', 1))
        operation.execute(WriteConcern.ACKNOWLEDGED)

        when:
        operation.execute()

        then:
        thrown(IllegalStateException)

        where:
        ordered << [true, false]
    }

    def 'should throw IllegalStateException when executing an empty bulk operation'() {
        given:
        def operation = initializeBulkOperation(ordered)

        when:
        operation.execute()

        then:
        thrown(IllegalStateException)

        where:
        ordered << [true, false]
    }

    def 'should throw IllegalStateException when executing an empty bulk operation with a write concern'() {
        given:
        def operation = initializeBulkOperation(ordered)

        when:
        operation.execute(WriteConcern.ACKNOWLEDGED)

        then:
        thrown(IllegalStateException)

        where:
        ordered << [true, false]
    }

    def 'should continue to execute batches after a failure if writes are unordered'() {
        given:
        collection.insert(new BasicDBObject('_id', 0))
        collection.insert(new BasicDBObject('_id', 1000))

        when:
        def operation = initializeBulkOperation(false)
        for (int i = 0; i < 2000; i++) {
            operation.insert(new BasicDBObject('_id', i))
        }
        operation.execute(WriteConcern.ACKNOWLEDGED)

        then:
        def ex = thrown(BulkWriteException)
        ex.writeErrors.size() == 2
        ex.getWriteResult().getInsertedCount() == 1998
        collection.count() == 2000
    }

    def 'should stop executing batches after a failure if writes are ordered'() {
        given:
        collection.insert(new BasicDBObject('_id', 500))
        collection.insert(new BasicDBObject('_id', 1500))

        when:
        def operation = initializeBulkOperation(true)
        for (int i = 0; i < 2000; i++) {
            operation.insert(new BasicDBObject('_id', i))
        }
        operation.execute(WriteConcern.ACKNOWLEDGED)

        then:
        def ex = thrown(BulkWriteException)
        ex.writeErrors.size() == 1
        ex.getWriteResult().getInsertedCount() == 500
        collection.count() == 502
    }

    private static void addWritesToOperation(BulkWriteOperation operation) {
        operation.find(new BasicDBObject('_id', 1)).updateOne(new BasicDBObject('$set', new BasicDBObject('x', 2)))
        operation.find(new BasicDBObject('_id', 2)).updateOne(new BasicDBObject('$set', new BasicDBObject('x', 3)))
        operation.find(new BasicDBObject('_id', 3)).removeOne()
        operation.find(new BasicDBObject('_id', 4)).removeOne()
        operation.find(new BasicDBObject('_id', 5)).replaceOne(new BasicDBObject('_id', 5).append('x', 4))
        operation.find(new BasicDBObject('_id', 6)).replaceOne(new BasicDBObject('_id', 6).append('x', 5))
        operation.insert(new BasicDBObject('_id', 7))
        operation.insert(new BasicDBObject('_id', 8))
    }

    private static List<BasicDBObject> getTestInserts() {
        [new BasicDBObject('_id', 1),
         new BasicDBObject('_id', 2),
         new BasicDBObject('_id', 3),
         new BasicDBObject('_id', 4),
         new BasicDBObject('_id', 5),
         new BasicDBObject('_id', 6)
        ]
    }

    private BulkWriteOperation initializeBulkOperation(boolean ordered) {
        ordered ? collection.initializeOrderedBulkOperation() : collection.initializeUnorderedBulkOperation()
    }

    private static Integer expectedModifiedCount(final int expectedCountForServersThatSupportIt) {
        (serverIsAtLeastVersion(2.5)) ? expectedCountForServersThatSupportIt : null
    }
}
