package ai.timefold.solver.constraint.streams.common.tri;

import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import java.util.function.BiFunction;
import java.util.function.Function;

import ai.timefold.solver.constraint.streams.common.AbstractJoiner;
import ai.timefold.solver.core.api.score.stream.tri.TriJoiner;
import ai.timefold.solver.core.impl.score.stream.JoinerType;

public final class DefaultTriJoiner<A, B, C> extends AbstractJoiner<C> implements TriJoiner<A, B, C> {

    private static final DefaultTriJoiner NONE =
            new DefaultTriJoiner(new BiFunction[0], new JoinerType[0], new Function[0]);

    private final BiFunction<A, B, ?>[] leftMappings;

    public <Property_> DefaultTriJoiner(BiFunction<A, B, Property_> leftMapping, JoinerType joinerType,
            Function<C, Property_> rightMapping) {
        super(rightMapping, joinerType);
        this.leftMappings = new BiFunction[] { leftMapping };
    }

    private <Property_> DefaultTriJoiner(BiFunction<A, B, Property_>[] leftMappings, JoinerType[] joinerTypes,
            Function<C, Property_>[] rightMappings) {
        super(rightMappings, joinerTypes);
        this.leftMappings = leftMappings;
    }

    public static <A, B, C> DefaultTriJoiner<A, B, C> merge(List<DefaultTriJoiner<A, B, C>> joinerList) {
        if (joinerList.size() == 1) {
            return joinerList.get(0);
        }
        return joinerList.stream().reduce(NONE, DefaultTriJoiner::and);
    }

    @Override
    public DefaultTriJoiner<A, B, C> and(TriJoiner<A, B, C> otherJoiner) {
        DefaultTriJoiner<A, B, C> castJoiner = (DefaultTriJoiner<A, B, C>) otherJoiner;
        int joinerCount = getJoinerCount();
        int castJoinerCount = castJoiner.getJoinerCount();
        int newJoinerCount = joinerCount + castJoinerCount;
        JoinerType[] newJoinerTypes = Arrays.copyOf(this.joinerTypes, newJoinerCount);
        BiFunction[] newLeftMappings = Arrays.copyOf(this.leftMappings, newJoinerCount);
        Function[] newRightMappings = Arrays.copyOf(this.rightMappings, newJoinerCount);
        for (int i = 0; i < castJoinerCount; i++) {
            int newJoinerIndex = i + joinerCount;
            newJoinerTypes[newJoinerIndex] = castJoiner.getJoinerType(i);
            newLeftMappings[newJoinerIndex] = castJoiner.getLeftMapping(i);
            newRightMappings[newJoinerIndex] = castJoiner.getRightMapping(i);
        }
        return new DefaultTriJoiner<>(newLeftMappings, newJoinerTypes, newRightMappings);
    }

    public BiFunction<A, B, Object> getLeftMapping(int index) {
        return (BiFunction<A, B, Object>) leftMappings[index];
    }

    public boolean matches(A a, B b, C c) {
        int joinerCount = getJoinerCount();
        for (int i = 0; i < joinerCount; i++) {
            JoinerType joinerType = getJoinerType(i);
            Object leftMapping = getLeftMapping(i).apply(a, b);
            Object rightMapping = getRightMapping(i).apply(c);
            if (!joinerType.matches(leftMapping, rightMapping)) {
                return false;
            }
        }
        return true;
    }

    @Override
    public boolean equals(Object o) {
        if (o instanceof DefaultTriJoiner<?, ?, ?> other) {
            return Arrays.equals(joinerTypes, other.joinerTypes)
                    && Arrays.equals(leftMappings, other.leftMappings)
                    && Arrays.equals(rightMappings, other.rightMappings);
        }
        return false;
    }

    @Override
    public int hashCode() {
        return Objects.hash(Arrays.hashCode(joinerTypes), Arrays.hashCode(leftMappings), Arrays.hashCode(rightMappings));
    }

}
