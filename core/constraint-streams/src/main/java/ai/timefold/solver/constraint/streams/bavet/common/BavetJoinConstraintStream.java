package ai.timefold.solver.constraint.streams.bavet.common;

import ai.timefold.solver.constraint.streams.bavet.common.bridge.BavetForeBridgeUniConstraintStream;

public interface BavetJoinConstraintStream<Solution_>
        extends BavetStreamBinaryOperation<Solution_>, TupleSource {

    /**
     *
     * @return An instance of {@link BavetForeBridgeUniConstraintStream}.
     */
    BavetAbstractConstraintStream<Solution_> getLeftParent();

    /**
     *
     * @return An instance of {@link BavetForeBridgeUniConstraintStream}.
     */
    BavetAbstractConstraintStream<Solution_> getRightParent();

}
