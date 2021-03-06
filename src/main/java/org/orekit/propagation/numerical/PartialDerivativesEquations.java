/* Copyright 2010-2011 Centre National d'Études Spatiales
 * Licensed to CS Systèmes d'Information (CS) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * CS licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.orekit.propagation.numerical;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.SortedSet;
import java.util.TreeSet;

import org.apache.commons.math3.analysis.differentiation.DerivativeStructure;
import org.apache.commons.math3.geometry.euclidean.threed.FieldRotation;
import org.apache.commons.math3.geometry.euclidean.threed.FieldVector3D;
import org.apache.commons.math3.geometry.euclidean.threed.Rotation;
import org.apache.commons.math3.geometry.euclidean.threed.Vector3D;
import org.apache.commons.math3.util.FastMath;
import org.apache.commons.math3.util.Precision;
import org.orekit.errors.OrekitException;
import org.orekit.errors.OrekitMessages;
import org.orekit.forces.ForceModel;
import org.orekit.propagation.SpacecraftState;
import org.orekit.propagation.integration.AdditionalEquations;

/** Set of {@link AdditionalEquations additional equations} computing the partial derivatives
 * of the state (orbit) with respect to initial state and force models parameters.
 * <p>
 * This set of equations are automatically added to a {@link NumericalPropagator numerical propagator}
 * in order to compute partial derivatives of the orbit along with the orbit itself. This is
 * useful for example in orbit determination applications.
 * </p>
 * @author V&eacute;ronique Pommier-Maurussane
 */
public class PartialDerivativesEquations implements AdditionalEquations {

    /** Selected parameters for Jacobian computation. */
    private NumericalPropagator propagator;

    /** Derivatives providers. */
    private final List<ForceModel> derivativesProviders;

    /** List of parameters selected for Jacobians computation. */
    private List<ParameterConfiguration> selectedParameters;

    /** Name. */
    private String name;

    /** State vector dimension without additional parameters
     * (either 6 or 7 depending on mass derivatives being included or not). */
    private int stateDim;

    /** Parameters vector dimension. */
    private int paramDim;

    /** Step used for finite difference computation with respect to spacecraft position. */
    private double hPos;

    /** Boolean for force models / selected parameters consistency. */
    private boolean dirty = false;

    /** Jacobian of acceleration with respect to spacecraft position. */
    private transient double[][] dAccdPos;

    /** Jacobian of acceleration with respect to spacecraft velocity. */
    private transient double[][] dAccdVel;

    /** Jacobian of acceleration with respect to spacecraft mass. */
    private transient double[]   dAccdM;

    /** Jacobian of acceleration with respect to one force model parameter (array reused for all parameters). */
    private transient double[]   dAccdParam;

    /** Simple constructor.
     * <p>
     * Upon construction, this set of equations is <em>automatically</em> added to
     * the propagator by calling its {@link
     * NumericalPropagator#addAdditionalEquations(AdditionalEquations)} method. So
     * there is no need to call this method explicitly for these equations.
     * </p>
     * @param name name of the partial derivatives equations
     * @param propagator the propagator that will handle the orbit propagation
     * @exception OrekitException if a set of equations with the same name is already present
     */
    public PartialDerivativesEquations(final String name, final NumericalPropagator propagator)
        throws OrekitException {
        this.name = name;
        derivativesProviders = new ArrayList<ForceModel>();
        dirty = true;
        this.propagator = propagator;
        selectedParameters = new ArrayList<ParameterConfiguration>();
        stateDim = -1;
        paramDim = -1;
        hPos     = Double.NaN;
        propagator.addAdditionalEquations(this);
    }

    /** {@inheritDoc} */
    public String getName() {
        return name;
    }

    /** Get the names of the available parameters in the propagator.
     * <p>
     * The names returned depend on the force models set up in the propagator,
     * including the Newtonian attraction from the central body.
     * </p>
     * @return available parameters
     */
    public List<String> getAvailableParameters() {
        final List<String> available = new ArrayList<String>();
        available.addAll(propagator.getNewtonianAttractionForceModel().getParametersNames());
        for (final ForceModel model : propagator.getForceModels()) {
            available.addAll(model.getParametersNames());
        }
        return available;
    }

    /** Select the parameters to consider for Jacobian processing.
     * <p>Parameters names have to be consistent with some
     * {@link ForceModel} added elsewhere.</p>
     * @param parameters parameters to consider for Jacobian processing
     * @see NumericalPropagator#addForceModel(ForceModel)
     * @see #setInitialJacobians(SpacecraftState, double[][], double[][])
     * @see ForceModel
     * @see org.apache.commons.math3.ode.Parameterizable

     */
    public void selectParameters(final String ... parameters) {

        selectedParameters.clear();
        for (String param : parameters) {
            selectedParameters.add(new ParameterConfiguration(param, Double.NaN));
        }

        dirty = true;

    }

    /** Select the parameters to consider for Jacobian processing.
     * <p>Parameters names have to be consistent with some
     * {@link ForceModel} added elsewhere.</p>
     * @param parameter parameter to consider for Jacobian processing
     * @param hP step to use for computing Jacobian column with respect to the specified parameter
     * @see NumericalPropagator#addForceModel(ForceModel)
     * @see #setInitialJacobians(SpacecraftState, double[][], double[][])
     * @see ForceModel
     * @see org.apache.commons.math3.ode.Parameterizable
     */
    public void selectParamAndStep(final String parameter, final double hP) {
        selectedParameters.add(new ParameterConfiguration(parameter, hP));
        dirty = true;
    }

    /** Set the initial value of the Jacobian with respect to state and parameter.
     * <p>
     * This method is equivalent to call {@link #setInitialJacobians(SpacecraftState,
     * double[][], double[][])} with dYdY0 set to the identity matrix and dYdP set
     * to a zero matrix.
     * </p>
     * <p>
     * The returned state must be added to the propagator (it is not done
     * automatically, as the user may need to add more states to it).
     * </p>
     * @param s0 initial state
     * @param stateDimension state dimension, must be either 6 for orbit only or 7 for orbit and mass
     * @param paramDimension parameters dimension
     * @return state with initial Jacobians added
     * @exception OrekitException if the partial equation has not been registered in
     * the propagator or if matrices dimensions are incorrect
     * @see #selectedParameters
     * @see #selectParamAndStep(String, double)
     */
    public SpacecraftState setInitialJacobians(final SpacecraftState s0, final int stateDimension, final int paramDimension)
        throws OrekitException {
        final double[][] dYdY0 = new double[stateDimension][stateDimension];
        final double[][] dYdP  = new double[stateDimension][paramDimension];
        for (int i = 0; i < stateDimension; ++i) {
            dYdY0[i][i] = 1.0;
        }
        return setInitialJacobians(s0, dYdY0, dYdP);
    }

    /** Set the initial value of the Jacobian with respect to state and parameter.
     * <p>
     * The returned state must be added to the propagator (it is not done
     * automatically, as the user may need to add more states to it).
     * </p>
     * @param s1 current state
     * @param dY1dY0 Jacobian of current state at time t₁ with respect
     * to state at some previous time t₀ (may be either 6x6 for orbit
     * only or 7x7 for orbit and mass)
     * @param dY1dP Jacobian of current state at time t₁ with respect
     * to parameters (may be null if no parameters are selected)
     * @return state with initial Jacobians added
     * @exception OrekitException if the partial equation has not been registered in
     * the propagator or if matrices dimensions are incorrect
     * @see #selectedParameters
     * @see #selectParamAndStep(String, double)
     */
    public SpacecraftState setInitialJacobians(final SpacecraftState s1,
                                               final double[][] dY1dY0, final double[][] dY1dP)
        throws OrekitException {

        // Check dimensions
        stateDim = dY1dY0.length;
        if ((stateDim < 6) || (stateDim > 7) || (stateDim != dY1dY0[0].length)) {
            throw new OrekitException(OrekitMessages.STATE_JACOBIAN_NEITHER_6X6_NOR_7X7,
                                      stateDim, dY1dY0[0].length);
        }
        if ((dY1dP != null) && (stateDim != dY1dP.length)) {
            throw new OrekitException(OrekitMessages.STATE_AND_PARAMETERS_JACOBIANS_ROWS_MISMATCH,
                                      stateDim, dY1dP.length);
        }

        paramDim = (dY1dP == null) ? 0 : dY1dP[0].length;

        // store the matrices as a single dimension array
        final JacobiansMapper mapper = getMapper();
        final double[] p = new double[mapper.getAdditionalStateDimension()];
        mapper.setInitialJacobians(s1, dY1dY0, dY1dP, p);

        // set value in propagator
        return s1.addAdditionalState(name, p);

    }

    /** Get a mapper between two-dimensional Jacobians and one-dimensional additional state.
     * @return a mapper between two-dimensional Jacobians and one-dimensional additional state,
     * with the same name as the instance
     * @exception OrekitException if the initial Jacobians have not been initialized yet
     * @see #setInitialJacobians(SpacecraftState, int, int)
     * @see #setInitialJacobians(SpacecraftState, double[][], double[][])
     */
    public JacobiansMapper getMapper() throws OrekitException {
        if (stateDim < 0) {
            throw new OrekitException(OrekitMessages.STATE_JACOBIAN_NOT_INITIALIZED);
        }
        return new JacobiansMapper(name, stateDim, paramDim,
                                   propagator.getOrbitType(),
                                   propagator.getPositionAngleType());
    }

    /** {@inheritDoc} */
    public double[] computeDerivatives(final SpacecraftState s, final double[] pDot)
        throws OrekitException {

        final int dim = 3;

        // Lazy initialization
        if (dirty) {

            // if step has not been set by user, set a default value
            if (Double.isNaN(hPos)) {
                hPos = FastMath.sqrt(Precision.EPSILON) * s.getPVCoordinates().getPosition().getNorm();
            }

             // set up derivatives providers
            derivativesProviders.clear();
            derivativesProviders.addAll(propagator.getForceModels());
            derivativesProviders.add(propagator.getNewtonianAttractionForceModel());

            // check all parameters are handled by at least one Jacobian provider
            for (final ParameterConfiguration param : selectedParameters) {
                final String parameterName = param.getParameterName();
                boolean found = false;
                for (final ForceModel provider : derivativesProviders) {
                    if (provider.isSupported(parameterName)) {
                        param.setProvider(provider);
                        found = true;
                    }
                }
                if (!found) {

                    // build the list of supported parameters, avoiding duplication
                    final SortedSet<String> set = new TreeSet<String>();
                    for (final ForceModel provider : derivativesProviders) {
                        for (final String forceModelParameter : provider.getParametersNames()) {
                            set.add(forceModelParameter);
                        }
                    }
                    final StringBuilder builder = new StringBuilder();
                    for (final String forceModelParameter : set) {
                        if (builder.length() > 0) {
                            builder.append(", ");
                        }
                        builder.append(forceModelParameter);
                    }

                    throw new OrekitException(OrekitMessages.UNSUPPORTED_PARAMETER_NAME,
                                              parameterName, builder.toString());

                }
            }

            // check the numbers of parameters and matrix size agree
            if (selectedParameters.size() != paramDim) {
                throw new OrekitException(OrekitMessages.INITIAL_MATRIX_AND_PARAMETERS_NUMBER_MISMATCH,
                                          paramDim, selectedParameters.size());
            }

            dAccdParam = new double[dim];
            dAccdPos   = new double[dim][dim];
            dAccdVel   = new double[dim][dim];
            dAccdM     = (stateDim > 6) ? new double[dim] : null;

            dirty = false;

        }

        // initialize acceleration Jacobians to zero
        for (final double[] row : dAccdPos) {
            Arrays.fill(row, 0.0);
        }
        for (final double[] row : dAccdVel) {
            Arrays.fill(row, 0.0);
        }
        if (dAccdM != null) {
            Arrays.fill(dAccdM, 0.0);
        }

        // prepare derivation variables, 3 for position, 3 for velocity and optionally 1 for mass
        final int nbVars = (dAccdM == null) ? 6 : 7;

        // position corresponds three free parameters
        final Vector3D position = s.getPVCoordinates().getPosition();
        final FieldVector3D<DerivativeStructure> dsP = new FieldVector3D<DerivativeStructure>(new DerivativeStructure(nbVars, 1, 0, position.getX()),
                                              new DerivativeStructure(nbVars, 1, 1, position.getY()),
                                              new DerivativeStructure(nbVars, 1, 2, position.getZ()));

        // velocity corresponds three free parameters
        final Vector3D velocity = s.getPVCoordinates().getVelocity();
        final FieldVector3D<DerivativeStructure> dsV = new FieldVector3D<DerivativeStructure>(new DerivativeStructure(nbVars, 1, 3, velocity.getX()),
                                              new DerivativeStructure(nbVars, 1, 4, velocity.getY()),
                                              new DerivativeStructure(nbVars, 1, 5, velocity.getZ()));

        // mass corresponds either to a constant or to one free parameter
        final DerivativeStructure dsM = (dAccdM == null) ?
                                        new DerivativeStructure(nbVars, 1,    s.getMass()) :
                                        new DerivativeStructure(nbVars, 1, 6, s.getMass());

        // we should compute attitude partial derivatives with respect to position/velocity
        // see issue #200
        final Rotation rotation = s.getAttitude().getRotation();
        final FieldRotation<DerivativeStructure> dsR =
                new FieldRotation<DerivativeStructure>(new DerivativeStructure(nbVars, 1, rotation.getQ0()),
                               new DerivativeStructure(nbVars, 1, rotation.getQ1()),
                               new DerivativeStructure(nbVars, 1, rotation.getQ2()),
                               new DerivativeStructure(nbVars, 1, rotation.getQ3()),
                               false);

        // compute acceleration Jacobians
        for (final ForceModel derivativesProvider : derivativesProviders) {
            final FieldVector3D<DerivativeStructure> acceleration =
                    derivativesProvider.accelerationDerivatives(s.getDate(), s.getFrame(),
                                                                dsP, dsV, dsR, dsM);
            addToRow(acceleration.getX(), 0);
            addToRow(acceleration.getY(), 1);
            addToRow(acceleration.getZ(), 2);
        }

        // the variational equations of the complete state Jacobian matrix have the
        // following form for 7x7, i.e. when mass partial derivatives are also considered
        // (when mass is not considered, only the A, B, D and E matrices are used along
        // with their derivatives):

        // [       |        |       ]   [                 |                  |               ]   [    |     |    ]
        // [ Adot  |  Bdot  |  Cdot ]   [  dVel/dPos = 0  |  dVel/dVel = Id  |   dVel/dm = 0 ]   [ A  |  B  |  C ]
        // [       |        |       ]   [                 |                  |               ]   [    |     |    ]
        // --------+--------+--- ----   ------------------+------------------+----------------   -----+-----+-----
        // [       |        |       ]   [                 |                  |               ]   [    |     |    ]
        // [ Ddot  |  Edot  |  Fdot ] = [    dAcc/dPos    |     dAcc/dVel    |    dAcc/dm    ] * [ D  |  E  |  F ]
        // [       |        |       ]   [                 |                  |               ]   [    |     |    ]
        // --------+--------+--- ----   ------------------+------------------+----------------   -----+-----+-----
        // [ Gdot  |  Hdot  |  Idot ]   [ dmDot/dPos = 0  |  dmDot/dVel = 0  |  dmDot/dm = 0 ]   [ G  |  H  |  I ]

        // The A, B, D and E sub-matrices and their derivatives (Adot ...) are 3x3 matrices,
        // the C and F sub-matrices and their derivatives (Cdot ...) are 3x1 matrices,
        // the G and H sub-matrices and their derivatives (Gdot ...) are 1x3 matrices,
        // the I sub-matrix and its derivative (Idot) is a 1x1 matrix.

        // The expanded multiplication above can be rewritten to take into account
        // the fixed values found in the sub-matrices in the left factor. This leads to:

        //     [ Adot ] = [ D ]
        //     [ Bdot ] = [ E ]
        //     [ Cdot ] = [ F ]
        //     [ Ddot ] = [ dAcc/dPos ] * [ A ] + [ dAcc/dVel ] * [ D ] + [ dAcc/dm ] * [ G ]
        //     [ Edot ] = [ dAcc/dPos ] * [ B ] + [ dAcc/dVel ] * [ E ] + [ dAcc/dm ] * [ H ]
        //     [ Fdot ] = [ dAcc/dPos ] * [ C ] + [ dAcc/dVel ] * [ F ] + [ dAcc/dm ] * [ I ]
        //     [ Gdot ] = [ 0 ]
        //     [ Hdot ] = [ 0 ]
        //     [ Idot ] = [ 0 ]

        // The following loops compute these expressions taking care of the mapping of the
        // (A, B, ... I) matrices into the single dimension array p and of the mapping of the
        // (Adot, Bdot, ... Idot) matrices into the single dimension array pDot.

        // copy D, E and F into Adot, Bdot and Cdot
        final double[] p = s.getAdditionalState(getName());
        System.arraycopy(p, dim * stateDim, pDot, 0, dim * stateDim);

        // compute Ddot, Edot and Fdot
        for (int i = 0; i < dim; ++i) {
            final double[] dAdPi = dAccdPos[i];
            final double[] dAdVi = dAccdVel[i];
            for (int j = 0; j < stateDim; ++j) {
                pDot[(dim + i) * stateDim + j] =
                    dAdPi[0] * p[j]                + dAdPi[1] * p[j +     stateDim] + dAdPi[2] * p[j + 2 * stateDim] +
                    dAdVi[0] * p[j + 3 * stateDim] + dAdVi[1] * p[j + 4 * stateDim] + dAdVi[2] * p[j + 5 * stateDim] +
                    ((dAccdM == null) ? 0.0 : dAccdM[i] * p[j + 6 * stateDim]);
            }
        }

        if (dAccdM != null) {
            // set Gdot, Hdot and Idot to 0
            Arrays.fill(pDot, 6 * stateDim, 7 * stateDim, 0.0);
        }

        for (int k = 0; k < paramDim; ++k) {

            // compute the acceleration gradient with respect to current parameter
            final ParameterConfiguration param = selectedParameters.get(k);
            final ForceModel provider = param.getProvider();
            final FieldVector3D<DerivativeStructure> accDer =
                    provider.accelerationDerivatives(s, param.getParameterName());
            dAccdParam[0] = accDer.getX().getPartialDerivative(1);
            dAccdParam[1] = accDer.getY().getPartialDerivative(1);
            dAccdParam[2] = accDer.getZ().getPartialDerivative(1);

            // the variational equations of the parameters Jacobian matrix are computed
            // one column at a time, they have the following form:
            // [      ]   [                 |                  |               ]   [   ]   [                  ]
            // [ Jdot ]   [  dVel/dPos = 0  |  dVel/dVel = Id  |   dVel/dm = 0 ]   [ J ]   [  dVel/dParam = 0 ]
            // [      ]   [                 |                  |               ]   [   ]   [                  ]
            // --------   ------------------+------------------+----------------   -----   --------------------
            // [      ]   [                 |                  |               ]   [   ]   [                  ]
            // [ Kdot ] = [    dAcc/dPos    |     dAcc/dVel    |    dAcc/dm    ] * [ K ] + [    dAcc/dParam   ]
            // [      ]   [                 |                  |               ]   [   ]   [                  ]
            // --------   ------------------+------------------+----------------   -----   --------------------
            // [ Ldot ]   [ dmDot/dPos = 0  |  dmDot/dVel = 0  |  dmDot/dm = 0 ]   [ L ]   [ dmDot/dParam = 0 ]

            // The J and K sub-columns and their derivatives (Jdot ...) are 3 elements columns,
            // the L sub-colums and its derivative (Ldot) are 1 elements columns.

            // The expanded multiplication and addition above can be rewritten to take into
            // account the fixed values found in the sub-matrices in the left factor. This leads to:

            //     [ Jdot ] = [ K ]
            //     [ Kdot ] = [ dAcc/dPos ] * [ J ] + [ dAcc/dVel ] * [ K ] + [ dAcc/dm ] * [ L ] + [ dAcc/dParam ]
            //     [ Ldot ] = [ 0 ]

            // The following loops compute these expressions taking care of the mapping of the
            // (J, K, L) columns into the single dimension array p and of the mapping of the
            // (Jdot, Kdot, Ldot) columns into the single dimension array pDot.

            // copy K into Jdot
            final int columnTop = stateDim * stateDim + k;
            pDot[columnTop]                = p[columnTop + 3 * paramDim];
            pDot[columnTop +     paramDim] = p[columnTop + 4 * paramDim];
            pDot[columnTop + 2 * paramDim] = p[columnTop + 5 * paramDim];

            // compute Kdot
            for (int i = 0; i < dim; ++i) {
                final double[] dAdPi = dAccdPos[i];
                final double[] dAdVi = dAccdVel[i];
                pDot[columnTop + (dim + i) * paramDim] =
                    dAccdParam[i] +
                    dAdPi[0] * p[columnTop]                + dAdPi[1] * p[columnTop +     paramDim] + dAdPi[2] * p[columnTop + 2 * paramDim] +
                    dAdVi[0] * p[columnTop + 3 * paramDim] + dAdVi[1] * p[columnTop + 4 * paramDim] + dAdVi[2] * p[columnTop + 5 * paramDim] +
                    ((dAccdM == null) ? 0.0 : dAccdM[i] * p[columnTop + 6 * paramDim]);
            }

            if (dAccdM != null) {
                // set Ldot to 0
                pDot[columnTop + 6 * paramDim] = 0;
            }

        }

        // these equations have no effect of the main state itself
        return null;

    }

    /** Fill Jacobians rows when mass is needed.
     * @param accelerationComponent component of acceleration (along either x, y or z)
     * @param index component index (0 for x, 1 for y, 2 for z)
     */
    private void addToRow(final DerivativeStructure accelerationComponent, final int index) {

        if (dAccdM == null) {

            // free parameters 0, 1, 2 are for position
            dAccdPos[index][0] += accelerationComponent.getPartialDerivative(1, 0, 0, 0, 0, 0);
            dAccdPos[index][1] += accelerationComponent.getPartialDerivative(0, 1, 0, 0, 0, 0);
            dAccdPos[index][2] += accelerationComponent.getPartialDerivative(0, 0, 1, 0, 0, 0);

            // free parameters 3, 4, 5 are for velocity
            dAccdVel[index][0] += accelerationComponent.getPartialDerivative(0, 0, 0, 1, 0, 0);
            dAccdVel[index][1] += accelerationComponent.getPartialDerivative(0, 0, 0, 0, 1, 0);
            dAccdVel[index][2] += accelerationComponent.getPartialDerivative(0, 0, 0, 0, 0, 1);

        } else {

            // free parameters 0, 1, 2 are for position
            dAccdPos[index][0] += accelerationComponent.getPartialDerivative(1, 0, 0, 0, 0, 0, 0);
            dAccdPos[index][1] += accelerationComponent.getPartialDerivative(0, 1, 0, 0, 0, 0, 0);
            dAccdPos[index][2] += accelerationComponent.getPartialDerivative(0, 0, 1, 0, 0, 0, 0);

            // free parameters 3, 4, 5 are for velocity
            dAccdVel[index][0] += accelerationComponent.getPartialDerivative(0, 0, 0, 1, 0, 0, 0);
            dAccdVel[index][1] += accelerationComponent.getPartialDerivative(0, 0, 0, 0, 1, 0, 0);
            dAccdVel[index][2] += accelerationComponent.getPartialDerivative(0, 0, 0, 0, 0, 1, 0);

            // free parameter 6 is for mass
            dAccdM[index]      += accelerationComponent.getPartialDerivative(0, 0, 0, 0, 0, 0, 1);

        }

    }

}

