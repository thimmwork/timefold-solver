package ai.timefold.solver.core.impl.heuristic.selector.move.generic.list.kopt;

import java.util.List;

import ai.timefold.solver.core.impl.util.CollectionUtils;
import ai.timefold.solver.core.impl.util.Pair;

final class KOptAffectedElements {
    private final int wrappedStartIndex;
    private final int wrappedEndIndex;
    private final List<Pair<Integer, Integer>> affectedMiddleRangeList;

    private KOptAffectedElements(int wrappedStartIndex, int wrappedEndIndex,
            List<Pair<Integer, Integer>> affectedMiddleRangeList) {
        this.wrappedStartIndex = wrappedStartIndex;
        this.wrappedEndIndex = wrappedEndIndex;
        this.affectedMiddleRangeList = affectedMiddleRangeList;
    }

    static KOptAffectedElements forMiddleRange(int startInclusive, int endExclusive) {
        return new KOptAffectedElements(-1, -1, List.of(new Pair<>(startInclusive, endExclusive)));
    }

    static KOptAffectedElements forWrappedRange(int startInclusive, int endExclusive) {
        return new KOptAffectedElements(startInclusive, endExclusive, List.of());
    }

    // ***********************************************
    // Simple getters
    // ***********************************************

    public int getWrappedStartIndex() {
        return wrappedStartIndex;
    }

    public int getWrappedEndIndex() {
        return wrappedEndIndex;
    }

    public List<Pair<Integer, Integer>> getAffectedMiddleRangeList() {
        return affectedMiddleRangeList;
    }

    // ***********************************************
    // Complex methods
    // ***********************************************
    public KOptAffectedElements merge(KOptAffectedElements other) {
        int newWrappedStartIndex = this.wrappedStartIndex;
        int newWrappedEndIndex = this.wrappedEndIndex;

        if (other.wrappedStartIndex != -1) {
            if (newWrappedStartIndex != -1) {
                newWrappedStartIndex = Math.min(other.wrappedStartIndex, newWrappedStartIndex);
                newWrappedEndIndex = Math.max(other.wrappedEndIndex, newWrappedEndIndex);
            } else {
                newWrappedStartIndex = other.wrappedStartIndex;
                newWrappedEndIndex = other.wrappedEndIndex;
            }
        }

        List<Pair<Integer, Integer>> newAffectedMiddleRangeList =
                CollectionUtils.concat(affectedMiddleRangeList, other.affectedMiddleRangeList);

        boolean removedAny;
        SearchForIntersectingInterval: do {
            removedAny = false;
            final int listSize = newAffectedMiddleRangeList.size();
            for (int i = 0; i < listSize; i++) {
                for (int j = i + 1; j < listSize; j++) {
                    Pair<Integer, Integer> leftInterval = newAffectedMiddleRangeList.get(i);
                    Pair<Integer, Integer> rightInterval = newAffectedMiddleRangeList.get(j);

                    if (leftInterval.key() <= rightInterval.value() &&
                            rightInterval.key() <= leftInterval.value()) {
                        Pair<Integer, Integer> mergedInterval =
                                new Pair<>(Math.min(leftInterval.key(), rightInterval.key()),
                                        Math.max(leftInterval.value(), rightInterval.value()));
                        newAffectedMiddleRangeList.set(i, mergedInterval);
                        newAffectedMiddleRangeList.remove(j);
                        removedAny = true;
                        continue SearchForIntersectingInterval;
                    }
                }
            }
        } while (removedAny);

        return new KOptAffectedElements(newWrappedStartIndex, newWrappedEndIndex, newAffectedMiddleRangeList);
    }

    @Override
    public String toString() {
        return "KOptAffectedElementsInfo{" +
                "wrappedStartIndex=" + wrappedStartIndex +
                ", wrappedEndIndex=" + wrappedEndIndex +
                ", affectedMiddleRangeList=" + affectedMiddleRangeList +
                '}';
    }
}
