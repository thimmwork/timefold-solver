package ai.timefold.solver.core.impl.score.director;

import static java.util.Objects.requireNonNull;

import java.util.IdentityHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Consumer;

import ai.timefold.solver.core.api.domain.lookup.PlanningId;
import ai.timefold.solver.core.api.domain.solution.cloner.SolutionCloner;
import ai.timefold.solver.core.api.domain.variable.VariableListener;
import ai.timefold.solver.core.api.score.Score;
import ai.timefold.solver.core.api.score.analysis.MatchAnalysis;
import ai.timefold.solver.core.api.score.director.ScoreDirector;
import ai.timefold.solver.core.config.solver.EnvironmentMode;
import ai.timefold.solver.core.impl.domain.common.accessor.MemberAccessor;
import ai.timefold.solver.core.impl.domain.constraintweight.descriptor.ConstraintConfigurationDescriptor;
import ai.timefold.solver.core.impl.domain.entity.descriptor.EntityDescriptor;
import ai.timefold.solver.core.impl.domain.lookup.LookUpManager;
import ai.timefold.solver.core.impl.domain.solution.descriptor.SolutionDescriptor;
import ai.timefold.solver.core.impl.domain.variable.descriptor.ListVariableDescriptor;
import ai.timefold.solver.core.impl.domain.variable.descriptor.VariableDescriptor;
import ai.timefold.solver.core.impl.domain.variable.listener.support.VariableListenerSupport;
import ai.timefold.solver.core.impl.domain.variable.listener.support.violation.SolutionTracker;
import ai.timefold.solver.core.impl.domain.variable.supply.SupplyManager;
import ai.timefold.solver.core.impl.heuristic.move.Move;
import ai.timefold.solver.core.impl.score.definition.ScoreDefinition;
import ai.timefold.solver.core.impl.solver.exception.UndoScoreCorruptionException;
import ai.timefold.solver.core.impl.solver.thread.ChildThreadType;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Abstract superclass for {@link ScoreDirector}.
 * <p>
 * Implementation note: Extending classes should follow these guidelines:
 * <ul>
 * <li>before* method: last statement should be a call to the super method</li>
 * <li>after* method: first statement should be a call to the super method</li>
 * </ul>
 *
 * @see ScoreDirector
 */
public abstract class AbstractScoreDirector<Solution_, Score_ extends Score<Score_>, Factory_ extends AbstractScoreDirectorFactory<Solution_, Score_>>
        implements InnerScoreDirector<Solution_, Score_>, Cloneable {

    private static final int CONSTRAINT_MATCH_DISPLAY_LIMIT = 8;
    protected final transient Logger logger = LoggerFactory.getLogger(getClass());

    private final boolean lookUpEnabled;
    private final LookUpManager lookUpManager;
    private final boolean expectShadowVariablesInCorrectState;
    protected final Factory_ scoreDirectorFactory;
    protected final VariableListenerSupport<Solution_> variableListenerSupport;
    protected final boolean constraintMatchEnabledPreference;

    private long workingEntityListRevision = 0L;
    private int workingGenuineEntityCount = 0;
    private boolean allChangesWillBeUndoneBeforeStepEnds = false;
    private long calculationCount = 0L;
    protected Solution_ workingSolution;
    protected Integer workingInitScore = null;
    private String undoMoveText;

    // Null when tracking disabled
    private final boolean trackingWorkingSolution;
    private final SolutionTracker<Solution_> solutionTracker;

    protected AbstractScoreDirector(Factory_ scoreDirectorFactory, boolean lookUpEnabled,
            boolean constraintMatchEnabledPreference, boolean expectShadowVariablesInCorrectState) {
        this.lookUpEnabled = lookUpEnabled;
        this.lookUpManager = lookUpEnabled
                ? new LookUpManager(scoreDirectorFactory.getSolutionDescriptor().getLookUpStrategyResolver())
                : null;
        this.expectShadowVariablesInCorrectState = expectShadowVariablesInCorrectState;
        this.scoreDirectorFactory = scoreDirectorFactory;
        this.variableListenerSupport = VariableListenerSupport.create(this);
        this.variableListenerSupport.linkVariableListeners();
        this.constraintMatchEnabledPreference = constraintMatchEnabledPreference;
        if (scoreDirectorFactory.isTrackingWorkingSolution()) {
            this.solutionTracker = new SolutionTracker<>(getSolutionDescriptor(),
                    getSupplyManager());
            this.trackingWorkingSolution = true;
        } else {
            this.solutionTracker = null;
            this.trackingWorkingSolution = false;
        }
    }

    @Override
    public Factory_ getScoreDirectorFactory() {
        return scoreDirectorFactory;
    }

    @Override
    public SolutionDescriptor<Solution_> getSolutionDescriptor() {
        return scoreDirectorFactory.getSolutionDescriptor();
    }

    @Override
    public ScoreDefinition<Score_> getScoreDefinition() {
        return scoreDirectorFactory.getScoreDefinition();
    }

    @Override
    public boolean expectShadowVariablesInCorrectState() {
        return expectShadowVariablesInCorrectState;
    }

    @Override
    public Solution_ getWorkingSolution() {
        return workingSolution;
    }

    @Override
    public long getWorkingEntityListRevision() {
        return workingEntityListRevision;
    }

    @Override
    public int getWorkingGenuineEntityCount() {
        return workingGenuineEntityCount;
    }

    @Override
    public void setAllChangesWillBeUndoneBeforeStepEnds(boolean allChangesWillBeUndoneBeforeStepEnds) {
        this.allChangesWillBeUndoneBeforeStepEnds = allChangesWillBeUndoneBeforeStepEnds;
    }

    @Override
    public long getCalculationCount() {
        return calculationCount;
    }

    @Override
    public void resetCalculationCount() {
        this.calculationCount = 0L;
    }

    @Override
    public void incrementCalculationCount() {
        this.calculationCount++;
    }

    @Override
    public SupplyManager getSupplyManager() {
        return variableListenerSupport;
    }

    // ************************************************************************
    // Complex methods
    // ************************************************************************

    /**
     * Note: resetting the working solution does NOT substitute the calls to before/after methods of
     * the {@link ai.timefold.solver.core.api.solver.change.ProblemChangeDirector} during
     * {@link ai.timefold.solver.core.api.solver.change.ProblemChange problem changes},
     * as these calls are propagated to {@link VariableListener variable listeners}, which update shadow variables
     * in the {@link ai.timefold.solver.core.api.domain.solution.PlanningSolution working solution} to keep it consistent.
     */
    @Override
    public void setWorkingSolution(Solution_ workingSolution) {
        this.workingSolution = requireNonNull(workingSolution);
        var solutionDescriptor = getSolutionDescriptor();

        /*
         * Both problem facts and entities need to be asserted,
         * which requires iterating over all of them,
         * possibly many thousands of objects.
         * Providing the init score and genuine entity count requires another pass over the entities.
         * The following code does all of those operations in a single pass.
         */
        Consumer<Object> visitor = this::assertNonNullPlanningId; // Every fact and entity will get this done.
        if (lookUpEnabled) {
            lookUpManager.reset();
            visitor = visitor.andThen(lookUpManager::addWorkingObject);
        }
        // This visits all the problem facts, applying the visitor.
        solutionDescriptor.visitAllProblemFacts(workingSolution, visitor);
        // This visits all the entities, applying the visitor.
        var initializationStatistics = solutionDescriptor.computeInitializationStatistics(workingSolution, visitor);
        setWorkingEntityListDirty();

        workingInitScore =
                -(initializationStatistics.unassignedValueCount() + initializationStatistics.uninitializedVariableCount());
        workingGenuineEntityCount = initializationStatistics.genuineEntityCount();
        variableListenerSupport.resetWorkingSolution();
    }

    @Override
    public void assertNonNullPlanningIds() {
        getSolutionDescriptor().visitAll(workingSolution, this::assertNonNullPlanningId);
    }

    private void assertNonNullPlanningId(Object fact) {
        Class<?> factClass = fact.getClass();
        MemberAccessor planningIdAccessor = getSolutionDescriptor().getPlanningIdAccessor(factClass);
        if (planningIdAccessor == null) { // There is no planning ID annotation.
            return;
        }
        Object id = planningIdAccessor.executeGetter(fact);
        if (id == null) { // Fail fast as planning ID is null.
            throw new IllegalStateException("The planningId (" + id + ") of the member (" + planningIdAccessor
                    + ") of the class (" + factClass + ") on object (" + fact + ") must not be null.\n"
                    + "Maybe initialize the planningId of the class (" + planningIdAccessor.getDeclaringClass()
                    + ") instance (" + fact + ") before solving.\n" +
                    "Maybe remove the @" + PlanningId.class.getSimpleName() + " annotation.");
        }
    }

    @Override
    public Score_ doAndProcessMove(Move<Solution_> move, boolean assertMoveScoreFromScratch) {
        if (trackingWorkingSolution) {
            solutionTracker.setBeforeMoveSolution(workingSolution);
        }
        Move<Solution_> undoMove = move.doMove(this);
        Score_ score = calculateScore();
        if (assertMoveScoreFromScratch) {
            undoMoveText = undoMove.toString();
            if (trackingWorkingSolution) {
                solutionTracker.setAfterMoveSolution(workingSolution);
            }
            assertWorkingScoreFromScratch(score, move);
        }
        undoMove.doMoveOnly(this);
        return score;
    }

    @Override
    public void doAndProcessMove(Move<Solution_> move, boolean assertMoveScoreFromScratch, Consumer<Score_> moveProcessor) {
        if (trackingWorkingSolution) {
            solutionTracker.setBeforeMoveSolution(workingSolution);
        }
        Move<Solution_> undoMove = move.doMove(this);
        Score_ score = calculateScore();
        if (assertMoveScoreFromScratch) {
            undoMoveText = undoMove.toString();
            if (trackingWorkingSolution) {
                solutionTracker.setAfterMoveSolution(workingSolution);
            }
            assertWorkingScoreFromScratch(score, move);
        }
        moveProcessor.accept(score);
        undoMove.doMoveOnly(this);
    }

    @Override
    public boolean isWorkingEntityListDirty(long expectedWorkingEntityListRevision) {
        return workingEntityListRevision != expectedWorkingEntityListRevision;
    }

    protected void setWorkingEntityListDirty() {
        workingEntityListRevision++;
    }

    @Override
    public Solution_ cloneWorkingSolution() {
        return cloneSolution(workingSolution);
    }

    @Override
    public Solution_ cloneSolution(Solution_ originalSolution) {
        SolutionDescriptor<Solution_> solutionDescriptor = getSolutionDescriptor();
        Score_ originalScore = (Score_) solutionDescriptor.getScore(originalSolution);
        Solution_ cloneSolution = solutionDescriptor.getSolutionCloner().cloneSolution(originalSolution);
        Score_ cloneScore = (Score_) solutionDescriptor.getScore(cloneSolution);
        if (scoreDirectorFactory.isAssertClonedSolution()) {
            if (!Objects.equals(originalScore, cloneScore)) {
                throw new IllegalStateException("Cloning corruption: "
                        + "the original's score (" + originalScore
                        + ") is different from the clone's score (" + cloneScore + ").\n"
                        + "Check the " + SolutionCloner.class.getSimpleName() + ".");
            }
            Map<Object, Object> originalEntityMap = new IdentityHashMap<>();
            solutionDescriptor.visitAllEntities(originalSolution,
                    originalEntity -> originalEntityMap.put(originalEntity, null));
            solutionDescriptor.visitAllEntities(cloneSolution, cloneEntity -> {
                if (originalEntityMap.containsKey(cloneEntity)) {
                    throw new IllegalStateException("Cloning corruption: "
                            + "the same entity (" + cloneEntity
                            + ") is present in both the original and the clone.\n"
                            + "So when a planning variable in the original solution changes, "
                            + "the cloned solution will change too.\n"
                            + "Check the " + SolutionCloner.class.getSimpleName() + ".");
                }
            });
        }
        return cloneSolution;
    }

    @Override
    public void triggerVariableListeners() {
        variableListenerSupport.triggerVariableListenersInNotificationQueues();
    }

    @Override
    public void forceTriggerVariableListeners() {
        variableListenerSupport.forceTriggerAllVariableListeners(getWorkingSolution());
    }

    protected void setCalculatedScore(Score_ score) {
        getSolutionDescriptor().setScore(workingSolution, score);
        calculationCount++;
    }

    @Override
    public AbstractScoreDirector<Solution_, Score_, Factory_> clone() {
        // Breaks incremental score calculation.
        // Subclasses should overwrite this method to avoid breaking it if possible.
        AbstractScoreDirector<Solution_, Score_, Factory_> clone =
                (AbstractScoreDirector<Solution_, Score_, Factory_>) scoreDirectorFactory
                        .buildScoreDirector(lookUpEnabled, constraintMatchEnabledPreference);
        clone.setWorkingSolution(cloneWorkingSolution());
        return clone;
    }

    @Override
    public InnerScoreDirector<Solution_, Score_> createChildThreadScoreDirector(ChildThreadType childThreadType) {
        if (childThreadType == ChildThreadType.PART_THREAD) {
            AbstractScoreDirector<Solution_, Score_, Factory_> childThreadScoreDirector =
                    (AbstractScoreDirector<Solution_, Score_, Factory_>) scoreDirectorFactory
                            .buildScoreDirector(lookUpEnabled, constraintMatchEnabledPreference);
            // ScoreCalculationCountTermination takes into account previous phases
            // but the calculationCount of partitions is maxed, not summed.
            childThreadScoreDirector.calculationCount = calculationCount;
            return childThreadScoreDirector;
        } else if (childThreadType == ChildThreadType.MOVE_THREAD) {
            // TODO The move thread must use constraintMatchEnabledPreference in FULL_ASSERT,
            // but it doesn't have to for Indictment Local Search, in which case it is a performance loss
            AbstractScoreDirector<Solution_, Score_, Factory_> childThreadScoreDirector =
                    (AbstractScoreDirector<Solution_, Score_, Factory_>) scoreDirectorFactory
                            .buildScoreDirector(true, constraintMatchEnabledPreference);
            childThreadScoreDirector.setWorkingSolution(cloneWorkingSolution());
            return childThreadScoreDirector;
        } else {
            throw new IllegalStateException("The childThreadType (" + childThreadType + ") is not implemented.");
        }
    }

    @Override
    public void close() {
        workingSolution = null;
        workingInitScore = null;
        if (lookUpEnabled) {
            lookUpManager.reset();
        }
        variableListenerSupport.close();
    }

    // ************************************************************************
    // Entity/variable add/change/remove methods
    // ************************************************************************

    @Override
    public final void beforeEntityAdded(Object entity) {
        beforeEntityAdded(getSolutionDescriptor().findEntityDescriptorOrFail(entity.getClass()), entity);
    }

    @Override
    public final void afterEntityAdded(Object entity) {
        afterEntityAdded(getSolutionDescriptor().findEntityDescriptorOrFail(entity.getClass()), entity);
    }

    @Override
    public final void beforeVariableChanged(Object entity, String variableName) {
        VariableDescriptor<Solution_> variableDescriptor = getSolutionDescriptor()
                .findVariableDescriptorOrFail(entity, variableName);
        beforeVariableChanged(variableDescriptor, entity);
    }

    @Override
    public final void afterVariableChanged(Object entity, String variableName) {
        VariableDescriptor<Solution_> variableDescriptor = getSolutionDescriptor()
                .findVariableDescriptorOrFail(entity, variableName);
        afterVariableChanged(variableDescriptor, entity);
    }

    @Override
    public void beforeListVariableElementAssigned(Object entity, String variableName, Object element) {
        ListVariableDescriptor<Solution_> listVariableDescriptor =
                (ListVariableDescriptor<Solution_>) getSolutionDescriptor().findVariableDescriptorOrFail(entity, variableName);
        beforeListVariableElementAssigned(listVariableDescriptor, element);
    }

    @Override
    public void afterListVariableElementAssigned(Object entity, String variableName, Object element) {
        ListVariableDescriptor<Solution_> listVariableDescriptor =
                (ListVariableDescriptor<Solution_>) getSolutionDescriptor().findVariableDescriptorOrFail(entity, variableName);
        afterListVariableElementAssigned(listVariableDescriptor, element);
    }

    @Override
    public void beforeListVariableElementUnassigned(Object entity, String variableName, Object element) {
        ListVariableDescriptor<Solution_> listVariableDescriptor =
                (ListVariableDescriptor<Solution_>) getSolutionDescriptor().findVariableDescriptorOrFail(entity, variableName);
        beforeListVariableElementUnassigned(listVariableDescriptor, element);
    }

    @Override
    public void afterListVariableElementUnassigned(Object entity, String variableName, Object element) {
        ListVariableDescriptor<Solution_> listVariableDescriptor =
                (ListVariableDescriptor<Solution_>) getSolutionDescriptor().findVariableDescriptorOrFail(entity, variableName);
        afterListVariableElementUnassigned(listVariableDescriptor, element);
    }

    @Override
    public void beforeListVariableChanged(Object entity, String variableName, int fromIndex, int toIndex) {
        ListVariableDescriptor<Solution_> listVariableDescriptor =
                (ListVariableDescriptor<Solution_>) getSolutionDescriptor().findVariableDescriptorOrFail(entity, variableName);
        beforeListVariableChanged(listVariableDescriptor, entity, fromIndex, toIndex);
    }

    @Override
    public void afterListVariableChanged(Object entity, String variableName, int fromIndex, int toIndex) {
        ListVariableDescriptor<Solution_> listVariableDescriptor =
                (ListVariableDescriptor<Solution_>) getSolutionDescriptor().findVariableDescriptorOrFail(entity, variableName);
        afterListVariableChanged(listVariableDescriptor, entity, fromIndex, toIndex);
    }

    @Override
    public final void beforeEntityRemoved(Object entity) {
        beforeEntityRemoved(getSolutionDescriptor().findEntityDescriptorOrFail(entity.getClass()), entity);
    }

    @Override
    public final void afterEntityRemoved(Object entity) {
        afterEntityRemoved(getSolutionDescriptor().findEntityDescriptorOrFail(entity.getClass()), entity);
    }

    public void beforeEntityAdded(EntityDescriptor<Solution_> entityDescriptor, Object entity) {
        variableListenerSupport.beforeEntityAdded(entityDescriptor, entity);
    }

    public void afterEntityAdded(EntityDescriptor<Solution_> entityDescriptor, Object entity) {
        workingInitScore -= entityDescriptor.countUninitializedVariables(entity);
        if (entityDescriptor.isGenuine()) {
            workingGenuineEntityCount++;
        }
        if (lookUpEnabled) {
            lookUpManager.addWorkingObject(entity);
        }
        if (!allChangesWillBeUndoneBeforeStepEnds) {
            setWorkingEntityListDirty();
        }
    }

    @Override
    public void beforeVariableChanged(VariableDescriptor<Solution_> variableDescriptor, Object entity) {
        if (variableDescriptor.isGenuineAndUninitialized(entity)) {
            workingInitScore++;
        }
        variableListenerSupport.beforeVariableChanged(variableDescriptor, entity);
    }

    @Override
    public void afterVariableChanged(VariableDescriptor<Solution_> variableDescriptor, Object entity) {
        if (variableDescriptor.isGenuineAndUninitialized(entity)) {
            workingInitScore--;
        }
    }

    @Override
    public void changeVariableFacade(VariableDescriptor<Solution_> variableDescriptor, Object entity, Object newValue) {
        beforeVariableChanged(variableDescriptor, entity);
        variableDescriptor.setValue(entity, newValue);
        afterVariableChanged(variableDescriptor, entity);
    }

    @Override
    public void beforeListVariableElementAssigned(ListVariableDescriptor<Solution_> variableDescriptor, Object element) {
        // Do nothing
    }

    @Override
    public void afterListVariableElementAssigned(ListVariableDescriptor<Solution_> variableDescriptor, Object element) {
        workingInitScore++;
    }

    @Override
    public void beforeListVariableElementUnassigned(ListVariableDescriptor<Solution_> variableDescriptor, Object element) {
        // Do nothing
    }

    @Override
    public void afterListVariableElementUnassigned(ListVariableDescriptor<Solution_> variableDescriptor, Object element) {
        workingInitScore--;
        variableListenerSupport.afterElementUnassigned(variableDescriptor, element);
    }

    @Override
    public void beforeListVariableChanged(ListVariableDescriptor<Solution_> variableDescriptor,
            Object entity, int fromIndex, int toIndex) {
        variableListenerSupport.beforeListVariableChanged(variableDescriptor, entity, fromIndex, toIndex);
    }

    @Override
    public void afterListVariableChanged(ListVariableDescriptor<Solution_> variableDescriptor,
            Object entity, int fromIndex, int toIndex) {
        variableListenerSupport.afterListVariableChanged(variableDescriptor, entity, fromIndex, toIndex);
    }

    public void beforeEntityRemoved(EntityDescriptor<Solution_> entityDescriptor, Object entity) {
        workingInitScore += entityDescriptor.countUninitializedVariables(entity);
        variableListenerSupport.beforeEntityRemoved(entityDescriptor, entity);
    }

    public void afterEntityRemoved(EntityDescriptor<Solution_> entityDescriptor, Object entity) {
        if (entityDescriptor.isGenuine()) {
            workingGenuineEntityCount--;
        }
        if (lookUpEnabled) {
            lookUpManager.removeWorkingObject(entity);
        }
        if (!allChangesWillBeUndoneBeforeStepEnds) {
            setWorkingEntityListDirty();
        }
    }

    // ************************************************************************
    // Problem fact add/change/remove methods
    // ************************************************************************

    @Override
    public void beforeProblemFactAdded(Object problemFact) {
        // Do nothing
    }

    @Override
    public void afterProblemFactAdded(Object problemFact) {
        if (lookUpEnabled) {
            lookUpManager.addWorkingObject(problemFact);
        }
        variableListenerSupport.resetWorkingSolution(); // TODO do not nuke the variable listeners
    }

    @Override
    public void beforeProblemPropertyChanged(Object problemFactOrEntity) {
        // Do nothing
    }

    @Override
    public void afterProblemPropertyChanged(Object problemFactOrEntity) {
        if (isConstraintConfiguration(problemFactOrEntity)) {
            setWorkingSolution(workingSolution); // Nuke everything and recalculate, constraint weights have changed.
        } else {
            variableListenerSupport.resetWorkingSolution(); // TODO do not nuke the variable listeners
        }
    }

    @Override
    public void beforeProblemFactRemoved(Object problemFact) {
        if (isConstraintConfiguration(problemFact)) {
            throw new IllegalStateException("Attempted to remove constraint configuration (" + problemFact +
                    ") from solution (" + workingSolution + ").\n" +
                    "Maybe use before/afterProblemPropertyChanged(...) instead.");
        }
    }

    @Override
    public void afterProblemFactRemoved(Object problemFact) {
        if (lookUpEnabled) {
            lookUpManager.removeWorkingObject(problemFact);
        }
        variableListenerSupport.resetWorkingSolution(); // TODO do not nuke the variable listeners
    }

    @Override
    public <E> E lookUpWorkingObject(E externalObject) {
        if (!lookUpEnabled) {
            throw new IllegalStateException("When lookUpEnabled (" + lookUpEnabled
                    + ") is disabled in the constructor, this method should not be called.");
        }
        return lookUpManager.lookUpWorkingObject(externalObject);
    }

    @Override
    public <E> E lookUpWorkingObjectOrReturnNull(E externalObject) {
        if (!lookUpEnabled) {
            throw new IllegalStateException("When lookUpEnabled (" + lookUpEnabled
                    + ") is disabled in the constructor, this method should not be called.");
        }
        return lookUpManager.lookUpWorkingObjectOrReturnNull(externalObject);
    }

    // ************************************************************************
    // Assert methods
    // ************************************************************************

    @Override
    public void assertExpectedWorkingScore(Score_ expectedWorkingScore, Object completedAction) {
        Score_ workingScore = calculateScore();
        if (!expectedWorkingScore.equals(workingScore)) {
            throw new IllegalStateException(
                    "Score corruption (" + expectedWorkingScore.subtract(workingScore).toShortString()
                            + "): the expectedWorkingScore (" + expectedWorkingScore
                            + ") is not the workingScore (" + workingScore
                            + ") after completedAction (" + completedAction + ").");
        }
    }

    @Override
    public void assertShadowVariablesAreNotStale(Score_ expectedWorkingScore, Object completedAction) {
        String violationMessage = variableListenerSupport.createShadowVariablesViolationMessage();
        if (violationMessage != null) {
            throw new IllegalStateException(
                    VariableListener.class.getSimpleName() + " corruption after completedAction ("
                            + completedAction + "):\n"
                            + violationMessage);
        }

        Score_ workingScore = calculateScore();
        if (!expectedWorkingScore.equals(workingScore)) {
            assertWorkingScoreFromScratch(workingScore,
                    "assertShadowVariablesAreNotStale(" + expectedWorkingScore + ", " + completedAction + ")");
            throw new IllegalStateException("Impossible " + VariableListener.class.getSimpleName() + " corruption ("
                    + expectedWorkingScore.subtract(workingScore).toShortString() + "):"
                    + " the expectedWorkingScore (" + expectedWorkingScore
                    + ") is not the workingScore (" + workingScore
                    + ") after all " + VariableListener.class.getSimpleName()
                    + "s were triggered without changes to the genuine variables"
                    + " after completedAction (" + completedAction + ").\n"
                    + "But all the shadow variable values are still the same, so this is impossible.\n"
                    + "Maybe run with " + EnvironmentMode.TRACKED_FULL_ASSERT
                    + " if you aren't already, to fail earlier.");
        }
    }

    /**
     * @param predicted true if the score was predicted and might have been calculated on another thread
     * @return never null
     */
    protected String buildShadowVariableAnalysis(boolean predicted) {
        String violationMessage = variableListenerSupport.createShadowVariablesViolationMessage();
        String workingLabel = predicted ? "working" : "corrupted";
        if (violationMessage == null) {
            return "Shadow variable corruption in the " + workingLabel + " scoreDirector:\n"
                    + "  None";
        }
        return "Shadow variable corruption in the " + workingLabel + " scoreDirector:\n"
                + violationMessage
                + "  Maybe there is a bug in the " + VariableListener.class.getSimpleName()
                + " of those shadow variable(s).";
    }

    @Override
    public void assertWorkingScoreFromScratch(Score_ workingScore, Object completedAction) {
        assertScoreFromScratch(workingScore, completedAction, false);
    }

    @Override
    public void assertPredictedScoreFromScratch(Score_ workingScore, Object completedAction) {
        assertScoreFromScratch(workingScore, completedAction, true);
    }

    private void assertScoreFromScratch(Score_ score, Object completedAction, boolean predicted) {
        InnerScoreDirectorFactory<Solution_, Score_> assertionScoreDirectorFactory = scoreDirectorFactory
                .getAssertionScoreDirectorFactory();
        if (assertionScoreDirectorFactory == null) {
            assertionScoreDirectorFactory = scoreDirectorFactory;
        }
        try (var uncorruptedScoreDirector = assertionScoreDirectorFactory.buildScoreDirector(false, true)) {
            uncorruptedScoreDirector.setWorkingSolution(workingSolution);
            Score_ uncorruptedScore = uncorruptedScoreDirector.calculateScore();
            if (!score.equals(uncorruptedScore)) {
                String scoreCorruptionAnalysis = buildScoreCorruptionAnalysis(uncorruptedScoreDirector, predicted);
                String shadowVariableAnalysis = buildShadowVariableAnalysis(predicted);
                throw new IllegalStateException(
                        "Score corruption (" + score.subtract(uncorruptedScore).toShortString()
                                + "): the " + (predicted ? "predictedScore" : "workingScore") + " (" + score
                                + ") is not the uncorruptedScore (" + uncorruptedScore
                                + ") after completedAction (" + completedAction + "):\n"
                                + scoreCorruptionAnalysis + "\n"
                                + shadowVariableAnalysis);
            }
        }
    }

    @Override
    public void assertExpectedUndoMoveScore(Move<Solution_> move, Score_ beforeMoveScore) {
        Score_ undoScore = calculateScore();
        if (!undoScore.equals(beforeMoveScore)) {
            logger.trace("        Corruption detected. Diagnosing...");

            if (trackingWorkingSolution) {
                solutionTracker.setAfterUndoSolution(workingSolution);
            }
            // Precondition: assert that there are probably no corrupted constraints
            assertWorkingScoreFromScratch(undoScore, undoMoveText);
            // Precondition: assert that shadow variables aren't stale after doing the undoMove
            assertShadowVariablesAreNotStale(undoScore, undoMoveText);
            String corruptionDiagnosis = "";
            if (trackingWorkingSolution) {
                // Recalculate all shadow variables from scratch.
                // We cannot set all shadow variables to null, since some variable listeners
                // may expect them to be non-null.
                // Instead, we just simulate a change to all genuine variables.
                variableListenerSupport.forceTriggerAllVariableListeners(workingSolution);
                solutionTracker.setUndoFromScratchSolution(workingSolution);

                // Also calculate from scratch for the before solution, since it might
                // have been corrupted but was only detected now
                solutionTracker.restoreBeforeSolution();
                variableListenerSupport.forceTriggerAllVariableListeners(workingSolution);
                solutionTracker.setBeforeFromScratchSolution(workingSolution);

                corruptionDiagnosis = solutionTracker.buildScoreCorruptionMessage();
            }
            String scoreDifference = undoScore.subtract(beforeMoveScore).toShortString();
            String corruptionMessage =
                    """
                            UndoMove corruption (%s): the beforeMoveScore (%s) is not the undoScore (%s) which is the uncorruptedScore (%s) of the workingSolution.
                            %s
                            1) Enable EnvironmentMode %s
                            (if you haven't already) to fail-faster in case there's a score corruption or variable listener corruption.
                            2) Check the Move.createUndoMove(...) method of the moveClass (%s).
                            The move (%s) might have a corrupted undoMove (%s).
                            3) Check your custom %ss (if you have any)
                            for shadow variables that are used by score constraints that could cause
                            the scoreDifference (%s).
                            """
                            .formatted(scoreDifference, beforeMoveScore, undoScore, undoScore,
                                    corruptionDiagnosis,
                                    EnvironmentMode.TRACKED_FULL_ASSERT,
                                    move.getClass().getSimpleName(), move, undoMoveText,
                                    VariableListener.class.getSimpleName(), scoreDifference);

            if (trackingWorkingSolution) {
                throw new UndoScoreCorruptionException(corruptionMessage,
                        solutionTracker.getBeforeMoveSolution(),
                        solutionTracker.getAfterMoveSolution(),
                        solutionTracker.getAfterUndoSolution());
            } else {
                throw new IllegalStateException(corruptionMessage);
            }
        }
    }

    /**
     * @param uncorruptedScoreDirector never null
     * @param predicted true if the score was predicted and might have been calculated on another thread
     * @return never null
     */
    protected String buildScoreCorruptionAnalysis(InnerScoreDirector<Solution_, Score_> uncorruptedScoreDirector,
            boolean predicted) {
        if (!isConstraintMatchEnabled() || !uncorruptedScoreDirector.isConstraintMatchEnabled()) {
            return """
                    Score corruption analysis could not be generated because either corrupted constraintMatchEnabled (%s) \
                    or uncorrupted constraintMatchEnabled (%s) is disabled.
                      Check your score constraints manually."""
                    .formatted(constraintMatchEnabledPreference, uncorruptedScoreDirector.isConstraintMatchEnabled());
        }

        var corruptedAnalysis = buildScoreAnalysis(true);
        var uncorruptedAnalysis = uncorruptedScoreDirector.buildScoreAnalysis(true);

        var excessSet = new LinkedHashSet<MatchAnalysis<Score_>>();
        var missingSet = new LinkedHashSet<MatchAnalysis<Score_>>();

        uncorruptedAnalysis.constraintMap().forEach((constraintRef, uncorruptedConstraintAnalysis) -> {
            var corruptedConstraintAnalysis = corruptedAnalysis.constraintMap()
                    .get(constraintRef);
            if (corruptedConstraintAnalysis == null || corruptedConstraintAnalysis.matches().isEmpty()) {
                missingSet.addAll(uncorruptedConstraintAnalysis.matches());
                return;
            }
            updateExcessAndMissingConstraintMatches(uncorruptedConstraintAnalysis.matches(),
                    corruptedConstraintAnalysis.matches(), excessSet, missingSet);
        });

        corruptedAnalysis.constraintMap().forEach((constraintRef, corruptedConstraintAnalysis) -> {
            var uncorruptedConstraintAnalysis = uncorruptedAnalysis.constraintMap()
                    .get(constraintRef);
            if (uncorruptedConstraintAnalysis == null || uncorruptedConstraintAnalysis.matches().isEmpty()) {
                excessSet.addAll(corruptedConstraintAnalysis.matches());
                return;
            }
            updateExcessAndMissingConstraintMatches(uncorruptedConstraintAnalysis.matches(),
                    corruptedConstraintAnalysis.matches(), excessSet, missingSet);
        });

        var analysis = new StringBuilder();
        analysis.append("Score corruption analysis:\n");
        // If predicted, the score calculation might have happened on another thread, so a different ScoreDirector
        // so there is no guarantee that the working ScoreDirector is the corrupted ScoreDirector
        var workingLabel = predicted ? "working" : "corrupted";
        appendAnalysis(analysis, workingLabel, "should not be there", excessSet);
        appendAnalysis(analysis, workingLabel, "are missing", missingSet);
        if (!missingSet.isEmpty() || !excessSet.isEmpty()) {
            analysis.append("""
                      Maybe there is a bug in the score constraints of those ConstraintMatch(s).
                      Maybe a score constraint doesn't select all the entities it depends on,
                        but discovers some transitively through a reference from the selected entity.
                        This corrupts incremental score calculation,
                        because the constraint is not re-evaluated if the transitively discovered entity changes.
                    """.stripTrailing());
        } else {
            if (predicted) {
                analysis.append("""
                          If multi-threaded solving is active:
                            - the working scoreDirector is probably not the corrupted scoreDirector.
                            - maybe the rebase() method of the move is bugged.
                            - maybe a VariableListener affected the moveThread's workingSolution after doing and undoing a move,
                              but this didn't happen here on the solverThread, so we can't detect it.
                        """.stripTrailing());
            } else {
                analysis.append("  Impossible state. Maybe this is a bug in the scoreDirector (%s)."
                        .formatted(getClass()));
            }
        }
        return analysis.toString();
    }

    private void appendAnalysis(StringBuilder analysis, String workingLabel, String suffix,
            Set<MatchAnalysis<Score_>> matches) {
        if (matches.isEmpty()) {
            analysis.append("""
                      The %s scoreDirector has no ConstraintMatch(es) which %s.
                    """.formatted(workingLabel, suffix));
        } else {
            analysis.append("""
                      The %s scoreDirector has %s ConstraintMatch(es) which %s:
                    """.formatted(workingLabel, matches.size(), suffix));
            matches.stream().sorted().limit(CONSTRAINT_MATCH_DISPLAY_LIMIT)
                    .forEach(match -> analysis.append("""
                                %s/%s=%s
                            """.formatted(match.constraintRef().constraintId(), match.justification(), match.score())));
            if (matches.size() >= CONSTRAINT_MATCH_DISPLAY_LIMIT) {
                analysis.append("""
                            ... %s more
                        """.formatted(matches.size() - CONSTRAINT_MATCH_DISPLAY_LIMIT));
            }
        }
    }

    private void updateExcessAndMissingConstraintMatches(List<MatchAnalysis<Score_>> uncorruptedList,
            List<MatchAnalysis<Score_>> corruptedList, Set<MatchAnalysis<Score_>> excessSet,
            Set<MatchAnalysis<Score_>> missingSet) {
        iterateAndAddIfFound(corruptedList, uncorruptedList, excessSet);
        iterateAndAddIfFound(uncorruptedList, corruptedList, missingSet);
    }

    private void iterateAndAddIfFound(List<MatchAnalysis<Score_>> referenceList, List<MatchAnalysis<Score_>> lookupList,
            Set<MatchAnalysis<Score_>> targetSet) {
        if (referenceList.isEmpty()) {
            return;
        }
        var lookupSet = new LinkedHashSet<>(lookupList); // Guaranteed to not contain duplicates anyway.
        for (var reference : referenceList) {
            if (!lookupSet.contains(reference)) {
                targetSet.add(reference);
            }
        }
    }

    protected boolean isConstraintConfiguration(Object problemFactOrEntity) {
        SolutionDescriptor<Solution_> solutionDescriptor = scoreDirectorFactory.getSolutionDescriptor();
        ConstraintConfigurationDescriptor<Solution_> constraintConfigurationDescriptor =
                solutionDescriptor.getConstraintConfigurationDescriptor();
        if (constraintConfigurationDescriptor == null) {
            return false;
        }
        return constraintConfigurationDescriptor.getConstraintConfigurationClass()
                .isInstance(problemFactOrEntity);
    }

    @Override
    public String toString() {
        return getClass().getSimpleName() + "(" + calculationCount + ")";
    }

}
