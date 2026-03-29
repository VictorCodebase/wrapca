package com.victorkithinji.wrap.wrapca.simulation;

import com.victorkithinji.wrap.wrapca.grid.CaGrid;
import com.victorkithinji.wrap.wrapca.grid.CellEnvironment;
import com.victorkithinji.wrap.wrapca.grid.CellStateEnum;
import com.victorkithinji.wrap.wrapca.ingestion.WindField;
import com.victorkithinji.wrap.wrapca.rothermel.FuelModel;
import com.victorkithinji.wrap.wrapca.rothermel.FuelModelResolver;
import com.victorkithinji.wrap.wrapca.rothermel.RothermelRosCalculator;
import com.victorkithinji.wrap.wrapca.rothermel.SlopeEffectCalculator;
import com.victorkithinji.wrap.wrapca.rothermel.WindProjectionCalculator;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Resolves the ignition probability for a single UNBURNED frontier cell by
 * iterating over its BURNING Moore neighbours.
 *
 * Algorithm per the proposal (Section 3.1.3):
 *   For each BURNING neighbour Nj of target cell Ci:
 *     1. Project wind onto the direction Nj → Ci  to get Ue.
 *     2. Compute slope φs from Nj elevation to Ci elevation.
 *     3. Resolve ROS (m/min) via RothermelRosCalculator using Nj's fuel params.
 *     4. Pe = clamp( (ROS × Δt) / distance, 0.0, 1.0 )
 *        where distance = distanceMetres of the neighbour descriptor.
 *   Combined probability (independent events):
 *     P(ignition) = 1 - ∏(1 - Pej)
 *
 * Returns 0.0 if the cell has no BURNING neighbours (should not happen if
 * the frontier is maintained correctly, but handled defensively).
 */
@Component
public class IgnitionProbabilityResolver {


    public IgnitionProbabilityResolver() {
    }

    /**
     * @param targetRow   row of the UNBURNED cell being evaluated
     * @param targetCol   col of the UNBURNED cell being evaluated
     * @param grid        current simulation grid
     * @param windField   wind speed and direction arrays for this time step
     * @param timeStepMin simulation time step in minutes
     * @return combined ignition probability in [0.0, 1.0]
     */
    public double resolve(int targetRow, int targetCol,
                          CaGrid grid,
                          WindField windField,
                          double timeStepMin) {

        List<MooreNeighbourEvaluator.NeighbourData> neighbours =
                MooreNeighbourEvaluator.getNeighbours(targetRow, targetCol, grid);

        int[][] states = grid.states;
        CellEnvironment[][] env = grid.environment;

        // Target cell environment (elevation used for slope calc)
        CellEnvironment targetEnv = env[targetRow][targetCol];

        double survivalProduct = 1.0; // ∏(1 - Pej)

        for (MooreNeighbourEvaluator.NeighbourData nb : neighbours) {
            if (states[nb.row][nb.col] != CellStateEnum.BURNING.ordinal()) {
                continue;
            }

            CellEnvironment sourceEnv = env[nb.row][nb.col];

            // 1. Effective wind component from source → target direction
            float windSpeed = windField.getSpeed(nb.row, nb.col);
            float windDir   = windField.getDirection(nb.row, nb.col);
            double ue  = WindProjectionCalculator.effectiveComponent(windSpeed, windDir, (nb.directionIndex + 4)%8 );

            // 2. Slope (source cell is the uphill reference for ROS direction)
//            double phi = SlopeEffectCalculator.slopeAngleRadians( // slopeAngleRadians returns radians
//                    sourceEnv.getSlopeRadians(),   // elevation proxy via slope
//                    targetEnv.getSlopeRadians(),
//                    nb.distanceMetres
//            );
            double phi = SlopeEffectCalculator.slopeAngleRadians( // slopeAngleRadians returns radians
                    0,   // elevation proxy via slope
                    0,
                    nb.distanceMetres
           );


            // 3. ROS from source fuel parameters
            FuelModel fuel = FuelModelResolver.resolve(sourceEnv.getVegetationType());
            double ros = RothermelRosCalculator.computeRos(fuel, sourceEnv.getNdmi(), ue, phi); // m/min

            // 4. Directional transition probability
            double pe = Math.min(1.0, (ros * timeStepMin) / nb.distanceMetres);
            pe = Math.max(0.0, pe);

            survivalProduct *= (1.0 - pe);
        }

        return 1.0 - survivalProduct;
    }
}