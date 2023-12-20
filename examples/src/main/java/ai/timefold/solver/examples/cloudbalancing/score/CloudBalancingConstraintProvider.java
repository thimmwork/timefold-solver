package ai.timefold.solver.examples.cloudbalancing.score;

import static ai.timefold.solver.core.api.score.stream.ConstraintCollectors.sum;
import static ai.timefold.solver.core.api.score.stream.Joiners.equal;

import java.util.Collection;
import java.util.function.Function;

import ai.timefold.solver.core.api.score.buildin.hardsoft.HardSoftScore;
import ai.timefold.solver.core.api.score.stream.Constraint;
import ai.timefold.solver.core.api.score.stream.ConstraintFactory;
import ai.timefold.solver.core.api.score.stream.ConstraintProvider;
import ai.timefold.solver.examples.cloudbalancing.domain.CloudComputer;
import ai.timefold.solver.examples.cloudbalancing.domain.CloudProcess;

import org.apache.commons.lang3.tuple.Pair;

public class CloudBalancingConstraintProvider implements ConstraintProvider {

    @Override
    public Constraint[] defineConstraints(ConstraintFactory constraintFactory) {
        return new Constraint[] {
                requiredCpuPowerTotal(constraintFactory),
                requiredMemoryTotal(constraintFactory),
                requiredNetworkBandwidthTotal(constraintFactory),
                computerCost(constraintFactory),
                differenceInRequiredCpu(constraintFactory)
        };
    }

    // ************************************************************************
    // Hard constraints
    // ************************************************************************

    Constraint requiredCpuPowerTotal(ConstraintFactory constraintFactory) {
        return constraintFactory.forEach(CloudProcess.class)
                .groupBy(CloudProcess::getComputer, sum(CloudProcess::getRequiredCpuPower))
                .filter((computer, requiredCpuPower) -> requiredCpuPower > computer.getCpuPower())
                .penalize(HardSoftScore.ONE_HARD,
                        (computer, requiredCpuPower) -> requiredCpuPower - computer.getCpuPower())
                .asConstraint("requiredCpuPowerTotal");
    }

    Constraint requiredMemoryTotal(ConstraintFactory constraintFactory) {
        return constraintFactory.forEach(CloudProcess.class)
                .groupBy(CloudProcess::getComputer, sum(CloudProcess::getRequiredMemory))
                .filter((computer, requiredMemory) -> requiredMemory > computer.getMemory())
                .penalize(HardSoftScore.ONE_HARD,
                        (computer, requiredMemory) -> requiredMemory - computer.getMemory())
                .asConstraint("requiredMemoryTotal");
    }

    Constraint requiredNetworkBandwidthTotal(ConstraintFactory constraintFactory) {
        return constraintFactory.forEach(CloudProcess.class)
                .groupBy(CloudProcess::getComputer, sum(CloudProcess::getRequiredNetworkBandwidth))
                .filter((computer, requiredNetworkBandwidth) -> requiredNetworkBandwidth > computer.getNetworkBandwidth())
                .penalize(HardSoftScore.ONE_HARD,
                        (computer, requiredNetworkBandwidth) -> requiredNetworkBandwidth - computer.getNetworkBandwidth())
                .asConstraint("requiredNetworkBandwidthTotal");
    }

    // ************************************************************************
    // Soft constraints
    // ************************************************************************

    Constraint computerCost(ConstraintFactory constraintFactory) {
        return constraintFactory.forEach(CloudComputer.class)
                .ifExists(CloudProcess.class, equal(Function.identity(), CloudProcess::getComputer))
                .penalize(HardSoftScore.ONE_SOFT, CloudComputer::getCost)
                .asConstraint("computerCost");
    }

    Constraint differenceInRequiredCpu(ConstraintFactory constraintFactory) {
        return constraintFactory.forEach(CloudProcess.class)
                .filter(cloudProcess -> cloudProcess.getComputer() != null)
                .groupBy(cloudProcess -> cloudProcess.getComputer().getId(), cloudProcess -> cloudProcess.getRequiredCpuPower())
                .groupBy(MinMaxCollector.collector())
                .penalize(HardSoftScore.ONE_HARD,
                        cpuPowerByComputer -> penalizeByCpuDifference(cpuPowerByComputer.values()))
                .asConstraint("differenceInRequiredCpu");
    }

    private Integer penalizeByCpuDifference(Collection<Pair<Integer, Integer>> minAndMaxCpuByComputer) {
        return minAndMaxCpuByComputer.stream()
                .mapToInt(minMax -> minMax.getRight() - minMax.getLeft())
                // use the square to penalize bigger differences way more than smaller differences
                .map(difference -> difference * difference)
                .sum();
    }

}
