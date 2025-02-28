package ai.timefold.solver.core.impl.score.stream;

public sealed interface IntCalculator<Output_> permits IntAverageCalculator, IntSumCalculator {
    void insert(int input);

    void retract(int input);

    Output_ result();
}
