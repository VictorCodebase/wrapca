package com.victorkithinji.wrap.wrapca.simulation;

import com.victorkithinji.wrap.wrapca.config.SimulationConfig;
import com.victorkithinji.wrap.wrapca.grid.CaGrid;
import com.victorkithinji.wrap.wrapca.grid.CellEnvironment;
import com.victorkithinji.wrap.wrapca.grid.CellStateEnum;
import com.victorkithinji.wrap.wrapca.ingestion.WindField;
import com.victorkithinji.wrap.wrapca.rothermel.FuelModel;
import com.victorkithinji.wrap.wrapca.rothermel.FuelModelResolver;
import com.victorkithinji.wrap.wrapca.rothermel.RothermelRosCalculator;
import com.victorkithinji.wrap.wrapca.rothermel.SlopeEffectCalculator;
import com.victorkithinji.wrap.wrapca.rothermel.WindProjectionCalculator;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Resolves the combined ignition probability for a single UNBURNED frontier cell
 * by iterating all of its BURNING Moore neighbours.
 *
 * Per BURNING neighbour Nj:
 *   1. Ue  = wind projected from Nj toward Ci
 *   2. phi = SlopeEffectCalculator.slopeAngleRadians(srcElev, targetElev, distance, isDiagonal)
 *   3. ROS = Rothermel ROS using Nj's fuel and moisture
 *   4. Pe  = clamp((ros * timeStepMin) / distanceMetres, 0, 1)
 *
 * Combined: P = 1 - prod(1 - Pe_j)
 */
@Component
@RequiredArgsConstructor
public class IgnitionProbabilityResolver {

    private final SimulationConfig simulationConfig;

    /**
     * Returns combined ignition probability in [0.0, 1.0].
     * Returns 0.0 if the target cell has no BURNING neighbours.
     */
    public double resolve(int targetRow, int targetCol,
                          CaGrid grid, WindField windField,
                          double timeStepMin) {

        List<NeighbourData> neighbours =
                MooreNeighbourEvaluator.getNeighbours(targetRow, targetCol, grid);

        double  survivalProduct     = 1.0;
        boolean hasBurningNeighbour = false;

        CellEnvironment targetEnv = grid.environment[targetRow][targetCol];

        for (NeighbourData nb : neighbours) {
            if (grid.getState(nb.getRow(), nb.getCol()) != CellStateEnum.BURNING) continue;

            hasBurningNeighbour = true;

            CellEnvironment srcEnv = grid.environment[nb.getRow()][nb.getCol()];

            // Direction fire travels: from this neighbour toward the target cell
            int     fireDir    = (nb.getDirectionIndex() + 4) % 8;
            boolean isDiagonal = (nb.getDirectionIndex() % 2 == 1);

            // 1. Effective wind component along fire-travel direction
            double windSpeed = windField.getSpeedMs()[nb.getRow()][nb.getCol()];
            double windDir   = windField.getDirectionDeg()[nb.getRow()][nb.getCol()];
            double ue = WindProjectionCalculator.effectiveComponent(windSpeed, windDir, fireDir);

            // 2. Signed slope angle from source cell toward target cell
            double phi = SlopeEffectCalculator.slopeAngleRadians(
                    srcEnv.getElevationMetres(),
                    targetEnv.getElevationMetres(),
                    grid.cellSizeMetres,
                    isDiagonal);

            // 3. ROS from source cell's fuel and moisture
            FuelModel fuel = FuelModelResolver.resolve(srcEnv.getVegetationType());
            double ros = RothermelRosCalculator.computeRos(fuel, srcEnv.getNdmi(), ue, phi);

            // 4. Per-neighbour ignition probability
            double pe = Math.min(1.0, Math.max(0.0,
                    (ros * timeStepMin) / nb.getDistanceMetres()));

            survivalProduct *= (1.0 - pe);
        }

        if (!hasBurningNeighbour) return 0.0;

        return 1.0 - survivalProduct;
    }
}