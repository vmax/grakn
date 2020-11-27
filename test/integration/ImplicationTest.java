/*
 * Copyright (C) 2020 Grakn Labs
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
 */

package grakn.core.test.integration;

import grakn.core.Grakn;
import grakn.core.common.parameters.Arguments;
import grakn.core.concept.ConceptManager;
import grakn.core.concept.schema.Rule;
import grakn.core.concept.type.AttributeType;
import grakn.core.concept.type.EntityType;
import grakn.core.concept.type.RelationType;
import grakn.core.reasoner.concludable.Concludable;
import grakn.core.reasoner.Implication;
import grakn.core.rocks.RocksGrakn;
import graql.lang.Graql;
import org.junit.Test;

import java.io.IOException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Set;

import static junit.framework.TestCase.assertEquals;

public class ImplicationTest {
    private static Path directory = Paths.get(System.getProperty("user.dir")).resolve("implication-test");
    private static String database = "implication-test";

    private long isaConcludablesCount(Set<Concludable<?>> concludables) {
        return concludables.stream().filter(Concludable::isIsa).count();
    }

    private long hasConcludablesCount(Set<Concludable<?>> concludables) {
        return concludables.stream().filter(Concludable::isHas).count();
    }

    private long relationConcludablesCount(Set<Concludable<?>> concludables) {
        return concludables.stream().filter(Concludable::isRelation).count();
    }

    private long valueConcludablesCount(Set<Concludable<?>> concludables) {
        return concludables.stream().filter(Concludable::isValue).count();
    }

    @Test
    public void implication_concludables_built_correctly_from_rule_concerning_relation() throws IOException {
        Util.resetDirectory(directory);

        try (Grakn grakn = RocksGrakn.open(directory)) {
            grakn.databases().create(database);
            try (Grakn.Session session = grakn.session(database, Arguments.Session.Type.SCHEMA)) {
                try (Grakn.Transaction txn = session.transaction(Arguments.Transaction.Type.WRITE)) {
                    final ConceptManager conceptMgr = txn.concepts();

                    final EntityType person = conceptMgr.putEntityType("person");
                    final RelationType friendship = conceptMgr.putRelationType("friendship");
                    friendship.setRelates("friend");
                    final RelationType marriage = conceptMgr.putRelationType("marriage");
                    marriage.setRelates("spouse");
                    person.setPlays(friendship.getRelates("friend"));
                    person.setPlays(marriage.getRelates("spouse"));
                    conceptMgr.putRule(
                            "marriage-is-friendship",
                            Graql.parsePattern("{$x isa person; $y isa person; (spouse: $x, spouse: $y) isa marriage; }").asConjunction(),
                            Graql.parseVariable("(friend: $x, friend: $y) isa friendship").asThing());
                    txn.commit();
                }
                try (Grakn.Transaction txn = session.transaction(Arguments.Transaction.Type.READ)) {
                    final ConceptManager conceptMgr = txn.concepts();
                    final Rule rule = conceptMgr.getRule("marriage-is-friendship");

                    Implication implication = new Implication(rule);

                    Set<Concludable<?>> headConcludables = implication.head();
                    assertEquals(1, isaConcludablesCount(headConcludables));
                    assertEquals(0, hasConcludablesCount(headConcludables));
                    assertEquals(1, relationConcludablesCount(headConcludables));
                    assertEquals(0, valueConcludablesCount(headConcludables));

                    Set<Concludable<?>> bodyConcludables = implication.body();
                    assertEquals(2, isaConcludablesCount(bodyConcludables));
                    assertEquals(0, hasConcludablesCount(bodyConcludables));
                    assertEquals(1, relationConcludablesCount(bodyConcludables));
                    assertEquals(0, valueConcludablesCount(bodyConcludables));
                }
            }
        }
    }

    @Test
    public void implication_concludables_built_correctly_from_rule_concerning_has_isa_value() throws IOException {
        Util.resetDirectory(directory);

        try (Grakn grakn = RocksGrakn.open(directory)) {
            grakn.databases().create(database);
            try (Grakn.Session session = grakn.session(database, Arguments.Session.Type.SCHEMA)) {
                try (Grakn.Transaction txn = session.transaction(Arguments.Transaction.Type.WRITE)) {
                    final ConceptManager conceptMgr = txn.concepts();

                    final EntityType milk = conceptMgr.putEntityType("milk");
                    final AttributeType ageInDays = conceptMgr.putAttributeType("age-in-days", AttributeType.ValueType.LONG);
                    final AttributeType isStillGood = conceptMgr.putAttributeType("is-still-good", AttributeType.ValueType.BOOLEAN);
                    milk.setOwns(ageInDays);
                    milk.setOwns(isStillGood);
                    conceptMgr.putRule(
                            "old-milk-is-not-good",
                            Graql.parsePattern("{ $x isa milk, has age-in-days >= 10; }").asConjunction(),
                            Graql.parseVariable("$x has is-still-good false").asThing());
                    txn.commit();
                }
                try (Grakn.Transaction txn = session.transaction(Arguments.Transaction.Type.READ)) {
                    final ConceptManager conceptMgr = txn.concepts();
                    final Rule rule = conceptMgr.getRule("old-milk-is-not-good");

                    Implication implication = new Implication(rule);

                    Set<Concludable<?>> headConcludables = implication.head();
                    assertEquals(1, isaConcludablesCount(headConcludables));
                    assertEquals(1, hasConcludablesCount(headConcludables));
                    assertEquals(0, relationConcludablesCount(headConcludables));
                    assertEquals(1, valueConcludablesCount(headConcludables));

                    Set<Concludable<?>> bodyConcludables = implication.body();
                    assertEquals(1, isaConcludablesCount(bodyConcludables));
                    assertEquals(1, hasConcludablesCount(bodyConcludables));
                    assertEquals(0, relationConcludablesCount(bodyConcludables));
                    assertEquals(0, valueConcludablesCount(bodyConcludables));
                }
            }
        }
    }

    @Test
    public void implication_concludables_built_correctly_from_rule_concerning_has() throws IOException {
        Util.resetDirectory(directory);

        try (Grakn grakn = RocksGrakn.open(directory)) {
            grakn.databases().create(database);
            try (Grakn.Session session = grakn.session(database, Arguments.Session.Type.SCHEMA)) {
                try (Grakn.Transaction txn = session.transaction(Arguments.Transaction.Type.WRITE)) {
                    final ConceptManager conceptMgr = txn.concepts();

                    final EntityType milk = conceptMgr.putEntityType("milk");
                    final AttributeType ageInDays = conceptMgr.putAttributeType("age-in-days", AttributeType.ValueType.LONG);
                    final AttributeType isStillGood = conceptMgr.putAttributeType("is-still-good", AttributeType.ValueType.BOOLEAN);
                    milk.setOwns(ageInDays);
                    milk.setOwns(isStillGood);
                    conceptMgr.putRule(
                            "old-milk-is-not-good",
                            Graql.parsePattern("{ $x isa milk; $a 10 isa age-in-days; }").asConjunction(),
                            Graql.parseVariable("$x has $a").asThing());
                    txn.commit();
                }
                try (Grakn.Transaction txn = session.transaction(Arguments.Transaction.Type.READ)) {
                    final ConceptManager conceptMgr = txn.concepts();
                    final Rule rule = conceptMgr.getRule("old-milk-is-not-good");

                    Implication implication = new Implication(rule);

                    Set<Concludable<?>> headConcludables = implication.head();
                    assertEquals(0, isaConcludablesCount(headConcludables));
                    assertEquals(1, hasConcludablesCount(headConcludables));
                    assertEquals(0, relationConcludablesCount(headConcludables));
                    assertEquals(0, valueConcludablesCount(headConcludables));

                    Set<Concludable<?>> bodyConcludables = implication.body();
                    assertEquals(2, isaConcludablesCount(bodyConcludables));
                    assertEquals(0, hasConcludablesCount(bodyConcludables));
                    assertEquals(0, relationConcludablesCount(bodyConcludables));
                    assertEquals(0, valueConcludablesCount(bodyConcludables));
                }
            }
        }
    }
}
