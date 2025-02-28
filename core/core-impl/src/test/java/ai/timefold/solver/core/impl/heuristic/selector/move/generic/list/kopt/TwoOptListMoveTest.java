package ai.timefold.solver.core.impl.heuristic.selector.move.generic.list.kopt;

import static ai.timefold.solver.core.impl.testdata.util.PlannerTestUtils.mockRebasingScoreDirector;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.verify;

import ai.timefold.solver.core.api.score.buildin.simple.SimpleScore;
import ai.timefold.solver.core.impl.domain.variable.descriptor.ListVariableDescriptor;
import ai.timefold.solver.core.impl.domain.variable.index.IndexVariableDemand;
import ai.timefold.solver.core.impl.domain.variable.index.IndexVariableSupply;
import ai.timefold.solver.core.impl.heuristic.move.AbstractMove;
import ai.timefold.solver.core.impl.score.director.InnerScoreDirector;
import ai.timefold.solver.core.impl.testdata.domain.list.TestdataListEntity;
import ai.timefold.solver.core.impl.testdata.domain.list.TestdataListSolution;
import ai.timefold.solver.core.impl.testdata.domain.list.TestdataListValue;
import ai.timefold.solver.core.impl.testdata.util.PlannerTestUtils;

import org.junit.jupiter.api.Test;

class TwoOptListMoveTest {

    private final ListVariableDescriptor<TestdataListSolution> variableDescriptor =
            TestdataListEntity.buildVariableDescriptorForValueList();

    private final InnerScoreDirector<TestdataListSolution, ?> scoreDirector =
            PlannerTestUtils.mockScoreDirector(variableDescriptor.getEntityDescriptor().getSolutionDescriptor());

    @Test
    void doMove() {
        TestdataListValue v1 = new TestdataListValue("1");
        TestdataListValue v2 = new TestdataListValue("2");
        TestdataListValue v3 = new TestdataListValue("3");
        TestdataListValue v4 = new TestdataListValue("4");
        TestdataListValue v5 = new TestdataListValue("5");
        TestdataListValue v6 = new TestdataListValue("6");
        TestdataListValue v7 = new TestdataListValue("7");
        TestdataListValue v8 = new TestdataListValue("8");
        TestdataListEntity e1 = TestdataListEntity.createWithValues("e1", v1, v2, v5, v4, v3, v6, v7, v8);

        // 2-Opt((v2, v5), (v3, v6))
        TwoOptListMove<TestdataListSolution> move = new TwoOptListMove<>(variableDescriptor,
                e1, e1, 2, 5);
        AbstractMove<TestdataListSolution> undoMove = move.doMove(scoreDirector);
        assertThat(e1.getValueList()).containsExactly(v1, v2, v3, v4, v5, v6, v7, v8);

        verify(scoreDirector).beforeListVariableChanged(variableDescriptor, e1, 2, 5);
        verify(scoreDirector).afterListVariableChanged(variableDescriptor, e1, 2, 5);
        verify(scoreDirector).triggerVariableListeners();

        undoMove.doMoveOnly(scoreDirector);
        assertThat(e1.getValueList()).containsExactly(v1, v2, v5, v4, v3, v6, v7, v8);
    }

    @Test
    void isMoveDoable() {
        TestdataListValue v1 = new TestdataListValue("1");
        TestdataListValue v2 = new TestdataListValue("2");
        TestdataListValue v3 = new TestdataListValue("3");
        TestdataListValue v4 = new TestdataListValue("4");
        TestdataListValue v5 = new TestdataListValue("5");
        TestdataListValue v6 = new TestdataListValue("6");
        TestdataListValue v7 = new TestdataListValue("7");
        TestdataListValue v8 = new TestdataListValue("8");
        TestdataListEntity e1 = TestdataListEntity.createWithValues("e1", v1, v2, v5, v4, v3, v6, v7, v8);

        // 2-Opt((v2, v5), (v3, v6))
        TwoOptListMove<TestdataListSolution> move = new TwoOptListMove<>(variableDescriptor,
                e1, e1, 2, 5);
        assertThat(move.isMoveDoable(scoreDirector)).isTrue();

        // 2-Opt((v2, v3), (v2, v3))
        move = new TwoOptListMove<>(variableDescriptor,
                e1, e1, 2, 2);
        assertThat(move.isMoveDoable(scoreDirector)).isFalse();

        // 2-Opt((v2, v3), (v3, v4))
        move = new TwoOptListMove<>(variableDescriptor,
                e1, e1, 2, 3);
        assertThat(move.isMoveDoable(scoreDirector)).isFalse();

        // 2-Opt((v2, v3), (v4, v5))
        move = new TwoOptListMove<>(variableDescriptor,
                e1, e1, 2, 4);
        assertThat(move.isMoveDoable(scoreDirector)).isTrue();

        // 2-Opt((v2, v3), (v1, v2))
        move = new TwoOptListMove<>(variableDescriptor,
                e1, e1, 2, 1);
        assertThat(move.isMoveDoable(scoreDirector)).isTrue();
    }

    @Test
    void isMoveDoableTailSwap() {
        TestdataListValue v1 = new TestdataListValue("1");
        TestdataListValue v2 = new TestdataListValue("2");
        TestdataListValue v3 = new TestdataListValue("3");
        TestdataListValue v4 = new TestdataListValue("4");
        TestdataListValue v5 = new TestdataListValue("5");
        TestdataListValue v6 = new TestdataListValue("6");
        TestdataListValue v7 = new TestdataListValue("7");
        TestdataListValue v8 = new TestdataListValue("8");
        TestdataListValue v9 = new TestdataListValue("9");
        TestdataListEntity e1 = TestdataListEntity.createWithValues("e1", v1, v2, v3, v4);
        TestdataListEntity e2 = TestdataListEntity.createWithValues("e2", v5, v6, v7, v8, v9);

        // 2-Opt((v2, v3), (v6, v7))
        TwoOptListMove<TestdataListSolution> move = new TwoOptListMove<>(variableDescriptor,
                e1, e2, 2, 2);
        assertThat(move.isMoveDoable(scoreDirector)).isTrue();

        move = new TwoOptListMove<>(variableDescriptor,
                e1, e2, 1, 2);
        assertThat(move.isMoveDoable(scoreDirector)).isTrue();

        move = new TwoOptListMove<>(variableDescriptor,
                e1, e2, 2, 1);
        assertThat(move.isMoveDoable(scoreDirector)).isTrue();
    }

    @Test
    void doTailSwap() {
        TestdataListValue v1 = new TestdataListValue("1");
        TestdataListValue v2 = new TestdataListValue("2");
        TestdataListValue v3 = new TestdataListValue("3");
        TestdataListValue v4 = new TestdataListValue("4");
        TestdataListValue v5 = new TestdataListValue("5");
        TestdataListValue v6 = new TestdataListValue("6");
        TestdataListValue v7 = new TestdataListValue("7");
        TestdataListValue v8 = new TestdataListValue("8");
        TestdataListValue v9 = new TestdataListValue("9");
        TestdataListEntity e1 = TestdataListEntity.createWithValues("e1", v1, v2, v3, v4);
        TestdataListEntity e2 = TestdataListEntity.createWithValues("e2", v5, v6, v7, v8, v9);

        // 2-Opt((v2, v3), (v6, v7))
        TwoOptListMove<TestdataListSolution> move = new TwoOptListMove<>(variableDescriptor,
                e1, e2, 2, 2);
        AbstractMove<TestdataListSolution> undoMove = move.doMove(scoreDirector);
        assertThat(e1.getValueList()).containsExactly(v1, v2, v7, v8, v9);
        assertThat(e2.getValueList()).containsExactly(v5, v6, v3, v4);

        verify(scoreDirector).beforeListVariableChanged(variableDescriptor, e1, 2, 4);
        verify(scoreDirector).afterListVariableChanged(variableDescriptor, e1, 2, 5);
        verify(scoreDirector).beforeListVariableChanged(variableDescriptor, e2, 2, 5);
        verify(scoreDirector).afterListVariableChanged(variableDescriptor, e2, 2, 4);
        verify(scoreDirector).triggerVariableListeners();

        undoMove.doMoveOnly(scoreDirector);
        assertThat(e1.getValueList()).containsExactly(v1, v2, v3, v4);
        assertThat(e2.getValueList()).containsExactly(v5, v6, v7, v8, v9);
    }

    @Test
    void doMoveSecondEndsBeforeFirst() {
        TestdataListValue v1 = new TestdataListValue("1");
        TestdataListValue v2 = new TestdataListValue("2");
        TestdataListValue v3 = new TestdataListValue("3");
        TestdataListValue v4 = new TestdataListValue("4");
        TestdataListValue v5 = new TestdataListValue("5");
        TestdataListValue v6 = new TestdataListValue("6");
        TestdataListValue v7 = new TestdataListValue("7");
        TestdataListValue v8 = new TestdataListValue("8");
        TestdataListEntity e1 = TestdataListEntity.createWithValues("e1", v8, v7, v3, v4, v5, v6, v2, v1);

        // 2-Opt((v6, v2), (v7, v3))
        TwoOptListMove<TestdataListSolution> move = new TwoOptListMove<>(variableDescriptor,
                e1, e1, 6, 2);
        AbstractMove<TestdataListSolution> undoMove = move.doMove(scoreDirector);
        assertThat(e1.getValueList()).containsExactly(v8, v1, v2, v3, v4, v5, v6, v7);

        verify(scoreDirector).beforeListVariableChanged(variableDescriptor, e1, 0, 8);
        verify(scoreDirector).afterListVariableChanged(variableDescriptor, e1, 0, 8);
        verify(scoreDirector).triggerVariableListeners();

        undoMove.doMoveOnly(scoreDirector);
        assertThat(e1.getValueList()).containsExactly(v8, v7, v3, v4, v5, v6, v2, v1);
    }

    @Test
    void doMoveSecondEndsBeforeFirstUnbalanced() {
        TestdataListValue v1 = new TestdataListValue("1");
        TestdataListValue v2 = new TestdataListValue("2");
        TestdataListValue v3 = new TestdataListValue("3");
        TestdataListValue v4 = new TestdataListValue("4");
        TestdataListValue v5 = new TestdataListValue("5");
        TestdataListValue v6 = new TestdataListValue("6");
        TestdataListValue v7 = new TestdataListValue("7");
        TestdataListEntity e1 = TestdataListEntity.createWithValues("e1", v5, v2, v3, v4, v1, v7, v6);

        // 2-Opt((v4, v1), (v5, v2))
        TwoOptListMove<TestdataListSolution> move = new TwoOptListMove<>(variableDescriptor,
                e1, e1, 4, 1);
        AbstractMove<TestdataListSolution> undoMove = move.doMove(scoreDirector);
        assertThat(e1.getValueList()).containsExactly(v5, v6, v7, v1, v2, v3, v4);

        verify(scoreDirector).beforeListVariableChanged(variableDescriptor, e1, 0, 7);
        verify(scoreDirector).afterListVariableChanged(variableDescriptor, e1, 0, 7);
        verify(scoreDirector).triggerVariableListeners();

        undoMove.doMoveOnly(scoreDirector);
        assertThat(e1.getValueList()).containsExactly(v5, v2, v3, v4, v1, v7, v6);
    }

    @Test
    void doMoveFirstEndsBeforeSecondUnbalanced() {
        TestdataListValue v1 = new TestdataListValue("1");
        TestdataListValue v2 = new TestdataListValue("2");
        TestdataListValue v3 = new TestdataListValue("3");
        TestdataListValue v4 = new TestdataListValue("4");
        TestdataListValue v5 = new TestdataListValue("5");
        TestdataListValue v6 = new TestdataListValue("6");
        TestdataListValue v7 = new TestdataListValue("7");
        TestdataListEntity e1 = TestdataListEntity.createWithValues("e1", v2, v1, v7, v4, v5, v6, v3);

        // 2-Opt((v4, v1), (v5, v2))
        TwoOptListMove<TestdataListSolution> move = new TwoOptListMove<>(variableDescriptor,
                e1, e1, 2, 1);
        AbstractMove<TestdataListSolution> undoMove = move.doMove(scoreDirector);
        assertThat(e1.getValueList()).containsExactly(v2, v3, v6, v5, v4, v7, v1);

        verify(scoreDirector).beforeListVariableChanged(variableDescriptor, e1, 0, 7);
        verify(scoreDirector).afterListVariableChanged(variableDescriptor, e1, 0, 7);
        verify(scoreDirector).triggerVariableListeners();

        undoMove.doMoveOnly(scoreDirector);
        assertThat(e1.getValueList()).containsExactly(v2, v1, v7, v4, v5, v6, v3);
    }

    @Test
    void rebase() {
        TestdataListValue v1 = new TestdataListValue("1");
        TestdataListValue v2 = new TestdataListValue("2");
        TestdataListEntity e1 = new TestdataListEntity("e1");

        TestdataListValue destinationV1 = new TestdataListValue("1");
        TestdataListValue destinationV2 = new TestdataListValue("2");
        TestdataListEntity destinationE1 = new TestdataListEntity("e1");

        InnerScoreDirector<TestdataListSolution, SimpleScore> destinationScoreDirector = mockRebasingScoreDirector(
                variableDescriptor.getEntityDescriptor().getSolutionDescriptor(), new Object[][] {
                        { v1, destinationV1 },
                        { v2, destinationV2 },
                        { e1, destinationE1 },
                });
        doReturn(scoreDirector.getSupplyManager()).when(destinationScoreDirector).getSupplyManager();

        IndexVariableSupply indexVariableSupply =
                scoreDirector.getSupplyManager().demand(new IndexVariableDemand<>(variableDescriptor));

        assertSameProperties(
                destinationE1, 0, 1,
                new TwoOptListMove<>(variableDescriptor, e1, e1, 0, 1)
                        .rebase(destinationScoreDirector));
    }

    static void assertSameProperties(
            Object destinationEntity, int destinationV1, int destinationV2,
            TwoOptListMove<?> move) {
        assertThat(move.getFirstEntity()).isSameAs(destinationEntity);
        assertThat(move.getFirstEdgeEndpoint()).isEqualTo(destinationV1);
        assertThat(move.getSecondEdgeEndpoint()).isEqualTo(destinationV2);
    }
}
