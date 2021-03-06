/*
 * Copyright (C) 2021 Grakn Labs
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as
 * published by the Free Software Foundation, either version 3 of the
 * License, or (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU Affero General Public License for more details.
 *
 * You should have received a copy of the GNU Affero General Public License
 * along with this program.  If not, see <https://www.gnu.org/licenses/>.
 *
 */

package grakn.core.graph.common;

import grakn.core.common.exception.GraknException;
import grakn.core.common.iterator.ResourceIterator;
import grakn.core.common.parameters.Label;
import grakn.core.graph.iid.PrefixIID;
import grakn.core.graph.iid.StructureIID;
import grakn.core.graph.iid.VertexIID;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import static grakn.core.common.collection.Bytes.join;
import static grakn.core.common.collection.Bytes.longToSortedBytes;
import static grakn.core.common.collection.Bytes.shortToSortedBytes;
import static grakn.core.common.collection.Bytes.sortedBytesToLong;
import static grakn.core.common.collection.Bytes.sortedBytesToShort;
import static grakn.core.common.exception.ErrorMessage.RuleWrite.MAX_RULE_REACHED;
import static grakn.core.common.exception.ErrorMessage.ThingWrite.MAX_INSTANCE_REACHED;
import static grakn.core.common.exception.ErrorMessage.TypeWrite.MAX_SUBTYPE_REACHED;
import static grakn.core.graph.common.Encoding.Key.BUFFERED;
import static grakn.core.graph.common.Encoding.Key.PERSISTED;
import static grakn.core.graph.common.Encoding.Vertex.Thing.ENTITY;
import static grakn.core.graph.common.Encoding.Vertex.Thing.RELATION;
import static grakn.core.graph.common.Encoding.Vertex.Thing.ROLE;
import static grakn.core.graph.iid.VertexIID.Thing.DEFAULT_LENGTH;
import static grakn.core.graph.iid.VertexIID.Thing.PREFIX_W_TYPE_LENGTH;
import static java.util.Arrays.copyOfRange;

public class KeyGenerator {

    public abstract static class Schema {

        private static final long SHORT_MAX_VALUE = Short.MAX_VALUE - 64;
        private static final long SHORT_MIN_VALUE = Short.MIN_VALUE + 64;

        protected final AtomicInteger ruleKey;
        protected final ConcurrentMap<PrefixIID, AtomicInteger> typeKeys;
        protected final int initialValue;
        protected final int delta;

        Schema(int initialValue, int delta) {
            typeKeys = new ConcurrentHashMap<>();
            ruleKey = new AtomicInteger(initialValue);
            this.initialValue = initialValue;
            this.delta = delta;
        }

        public byte[] forType(PrefixIID rootIID, Label rootLabel) {
            int key;
            if ((key = typeKeys.computeIfAbsent(rootIID, k -> new AtomicInteger(initialValue)).getAndAdd(delta)) >= SHORT_MAX_VALUE
                    || key <= SHORT_MIN_VALUE) {
                typeKeys.get(rootIID).addAndGet(-1 * delta);
                throw GraknException.of(MAX_SUBTYPE_REACHED, rootLabel, SHORT_MAX_VALUE);
            }
            return shortToSortedBytes(key);
        }

        public byte[] forRule() {
            int key;
            if ((key = ruleKey.getAndAdd(delta)) >= SHORT_MAX_VALUE || key <= SHORT_MIN_VALUE) {
                ruleKey.addAndGet(-1 * delta);
                throw GraknException.of(MAX_RULE_REACHED, SHORT_MAX_VALUE);
            }
            return shortToSortedBytes(key);
        }

        public static class Buffered extends Schema {

            public Buffered() {
                super(BUFFERED.initialValue(), BUFFERED.isIncrement() ? 1 : -1);
            }
        }

        public static class Persisted extends Schema {

            public Persisted() {
                super(PERSISTED.initialValue(), PERSISTED.isIncrement() ? 1 : -1);
            }

            public void sync(Storage storage) {
                syncTypeKeys(storage);
                syncRuleKey(storage);
            }

            private void syncTypeKeys(Storage storage) {
                for (Encoding.Vertex.Type encoding : Encoding.Vertex.Type.values()) {
                    byte[] prefix = encoding.prefix().bytes();
                    byte[] lastIID = storage.getLastKey(prefix);
                    AtomicInteger nextValue = lastIID != null ?
                            new AtomicInteger(sortedBytesToShort(copyOfRange(lastIID, PrefixIID.LENGTH, VertexIID.Type.LENGTH)) + delta) :
                            new AtomicInteger(initialValue);
                    typeKeys.put(PrefixIID.of(encoding), nextValue);
                }
            }

            private void syncRuleKey(Storage storage) {
                byte[] prefix = Encoding.Structure.RULE.prefix().bytes();
                byte[] lastIID = storage.getLastKey(prefix);
                if (lastIID != null) {
                    ruleKey.set(sortedBytesToShort(copyOfRange(lastIID, PrefixIID.LENGTH, StructureIID.Rule.LENGTH)) + delta);
                } else {
                    ruleKey.set(initialValue);
                }
            }
        }
    }

    public abstract static class Data {

        private static final long LONG_MAX_VALUE = Long.MAX_VALUE - 64;
        private static final long LONG_MIN_VALUE = Long.MIN_VALUE + 64;

        protected final ConcurrentMap<PrefixIID, AtomicInteger> typeKeys;
        protected final ConcurrentMap<VertexIID.Type, AtomicLong> thingKeys;
        protected final int initialValue;
        protected final int delta;

        Data(int initialValue, int delta) {
            typeKeys = new ConcurrentHashMap<>();
            thingKeys = new ConcurrentHashMap<>();
            this.initialValue = initialValue;
            this.delta = delta;
        }

        public byte[] forThing(VertexIID.Type typeIID, Label typeLabel) {
            long key;
            if ((key = thingKeys.computeIfAbsent(typeIID, k -> new AtomicLong(initialValue)).getAndAdd(delta)) >= LONG_MAX_VALUE
                    || key <= LONG_MIN_VALUE) {
                thingKeys.get(typeIID).addAndGet(-1 * delta);
                throw GraknException.of(MAX_INSTANCE_REACHED, typeLabel, LONG_MAX_VALUE);
            }
            return longToSortedBytes(key);
        }

        public static class Buffered extends Data {

            public Buffered() {
                super(BUFFERED.initialValue(), BUFFERED.isIncrement() ? 1 : -1);
            }
        }

        public static class Persisted extends Data {

            public Persisted() {
                super(PERSISTED.initialValue(), PERSISTED.isIncrement() ? 1 : -1);
            }

            public void sync(Storage storage) {
                Encoding.Vertex.Thing[] thingsWithGeneratedIID = new Encoding.Vertex.Thing[]{ENTITY, RELATION, ROLE};

                for (Encoding.Vertex.Thing thingEncoding : thingsWithGeneratedIID) {
                    byte[] typeEncoding = Encoding.Vertex.Type.of(thingEncoding).prefix().bytes();
                    ResourceIterator<byte[]> typeIterator = storage.iterate(typeEncoding, (iid, value) -> iid)
                            .filter(iid1 -> iid1.length == VertexIID.Type.LENGTH);
                    while (typeIterator.hasNext()) {
                        byte[] typeIID = typeIterator.next();
                        byte[] prefix = join(thingEncoding.prefix().bytes(), typeIID);
                        byte[] lastIID = storage.getLastKey(prefix);
                        AtomicLong nextValue = lastIID != null ?
                                new AtomicLong(sortedBytesToLong(copyOfRange(lastIID, PREFIX_W_TYPE_LENGTH, DEFAULT_LENGTH)) + delta) :
                                new AtomicLong(initialValue);
                        thingKeys.put(VertexIID.Type.of(typeIID), nextValue);
                    }
                }
            }
        }
    }
}
