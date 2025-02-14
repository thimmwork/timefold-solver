package ai.timefold.solver.core.impl.exhaustivesearch;

import static org.assertj.core.api.Assertions.fail;
import static org.assertj.core.api.SoftAssertions.assertSoftly;

import ai.timefold.solver.core.api.score.buildin.simple.SimpleScore;
import ai.timefold.solver.core.api.solver.SolverFactory;
import ai.timefold.solver.core.config.exhaustivesearch.ExhaustiveSearchPhaseConfig;
import ai.timefold.solver.core.config.exhaustivesearch.ExhaustiveSearchType;
import ai.timefold.solver.core.config.solver.SolverConfig;
import ai.timefold.solver.core.impl.exhaustivesearch.scope.ExhaustiveSearchStepScope;
import ai.timefold.solver.core.impl.phase.event.PhaseLifecycleListenerAdapter;
import ai.timefold.solver.core.impl.phase.scope.AbstractStepScope;
import ai.timefold.solver.core.impl.solver.DefaultSolver;
import ai.timefold.solver.core.impl.testdata.domain.TestdataEasyScoreCalculator;
import ai.timefold.solver.core.impl.testdata.domain.TestdataEntity;
import ai.timefold.solver.core.impl.testdata.domain.TestdataSolution;
import ai.timefold.solver.core.impl.testdata.domain.nullable.TestdataNullableEasyScoreCalculator;
import ai.timefold.solver.core.impl.testdata.domain.nullable.TestdataNullableEntity;
import ai.timefold.solver.core.impl.testdata.domain.nullable.TestdataNullableSolution;

import org.junit.jupiter.api.Test;

class BruteForceTest {

    @Test
    void doesNotIncludeNullForNonNullableVariable() {
        var solverConfig = new SolverConfig()
                .withSolutionClass(TestdataSolution.class)
                .withEntityClasses(TestdataEntity.class)
                .withEasyScoreCalculatorClass(TestdataEasyScoreCalculator.class)
                .withPhases(new ExhaustiveSearchPhaseConfig()
                        .withExhaustiveSearchType(ExhaustiveSearchType.BRUTE_FORCE));
        var solver = (DefaultSolver<TestdataSolution>) SolverFactory.<TestdataSolution> create(solverConfig)
                .buildSolver();

        var solution = TestdataSolution.generateSolution(2, 2);
        for (TestdataEntity entity : solution.getEntityList()) { // Make sure nothing is set.
            entity.setValue(null);
        }

        solver.addPhaseLifecycleListener(new PhaseLifecycleListenerAdapter<>() {

            @Override
            public void stepStarted(AbstractStepScope<TestdataSolution> stepScope) {
                if (stepScope instanceof ExhaustiveSearchStepScope<TestdataSolution> exhaustiveSearchStepScope) {
                    if (exhaustiveSearchStepScope.getStepIndex() == 3) {
                        fail("The exhaustive search phase was not ended after 3 steps.");
                    }
                } else {
                    fail("Wrong phase was started: " + stepScope.getClass().getSimpleName());
                }
            }

        });

        var finalBestSolution = solver.solve(solution);

        assertSoftly(softly -> {
            softly.assertThat(finalBestSolution.getScore())
                    .isEqualTo(SimpleScore.ZERO);
            softly.assertThat(finalBestSolution.getEntityList().get(0).getValue())
                    .isEqualTo(solution.getValueList().get(0));
            softly.assertThat(finalBestSolution.getEntityList().get(1).getValue())
                    .isEqualTo(solution.getValueList().get(1));
        });
    }

    @Test
    void includesNullsForNullableVariable() {
        var solverConfig = new SolverConfig()
                .withSolutionClass(TestdataNullableSolution.class)
                .withEntityClasses(TestdataNullableEntity.class)
                .withEasyScoreCalculatorClass(TestdataNullableEasyScoreCalculator.class)
                .withPhases(new ExhaustiveSearchPhaseConfig()
                        .withExhaustiveSearchType(ExhaustiveSearchType.BRUTE_FORCE));
        var solver = (DefaultSolver<TestdataNullableSolution>) SolverFactory.<TestdataNullableSolution> create(solverConfig)
                .buildSolver();

        var solution = TestdataNullableSolution.generateSolution(1, 2);
        for (TestdataNullableEntity entity : solution.getEntityList()) { // Make sure nothing is set.
            entity.setValue(null);
        }

        solver.addPhaseLifecycleListener(new PhaseLifecycleListenerAdapter<>() {

            @Override
            public void stepStarted(AbstractStepScope<TestdataNullableSolution> stepScope) {
                if (stepScope instanceof ExhaustiveSearchStepScope<TestdataNullableSolution> exhaustiveSearchStepScope) {
                    if (exhaustiveSearchStepScope.getStepIndex() == 3) {
                        fail("The exhaustive search phase was not ended after 3 steps.");
                    }
                } else {
                    fail("Wrong phase was started: " + stepScope.getClass().getSimpleName());
                }
            }

        });

        var finalBestSolution = solver.solve(solution);

        assertSoftly(softly -> {
            softly.assertThat(finalBestSolution.getScore())
                    .isEqualTo(SimpleScore.of(-1));
            softly.assertThat(finalBestSolution.getEntityList().get(0).getValue())
                    .as("The first entity's value was set.")
                    .isNull();
            softly.assertThat(finalBestSolution.getEntityList().get(1).getValue())
                    .as("The second entity's value was not set.")
                    .isNotNull();
        });
    }

}
