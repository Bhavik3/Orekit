/* Copyright 2002-2014 CS Systèmes d'Information
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
package org.orekit.propagation.analytical;

import java.io.Serializable;
import java.util.Collection;

import org.apache.commons.math3.analysis.differentiation.DerivativeStructure;
import org.apache.commons.math3.analysis.interpolation.HermiteInterpolator;
import org.apache.commons.math3.geometry.euclidean.threed.FieldVector3D;
import org.apache.commons.math3.geometry.euclidean.threed.Vector3D;
import org.apache.commons.math3.util.FastMath;
import org.apache.commons.math3.util.MathUtils;
import org.orekit.attitudes.AttitudeProvider;
import org.orekit.errors.OrekitException;
import org.orekit.errors.OrekitMessages;
import org.orekit.errors.PropagationException;
import org.orekit.forces.gravity.potential.UnnormalizedSphericalHarmonicsProvider;
import org.orekit.forces.gravity.potential.UnnormalizedSphericalHarmonicsProvider.UnnormalizedSphericalHarmonics;
import org.orekit.frames.Frame;
import org.orekit.orbits.CircularOrbit;
import org.orekit.orbits.Orbit;
import org.orekit.orbits.OrbitType;
import org.orekit.orbits.PositionAngle;
import org.orekit.propagation.SpacecraftState;
import org.orekit.time.AbsoluteDate;
import org.orekit.utils.TimeStampedPVCoordinates;

/** This class propagates a {@link org.orekit.propagation.SpacecraftState}
 *  using the analytical Eckstein-Hechler model.
 * <p>The Eckstein-Hechler model is suited for near circular orbits
 * (e < 0.1, with poor accuracy between 0.005 and 0.1) and inclination
 * neither equatorial (direct or retrograde) nor critical (direct or
 * retrograde).</p>
 * @see Orbit
 * @author Guylaine Prat
 */
public class EcksteinHechlerPropagator extends AbstractAnalyticalPropagator {

    /** Eckstein-Hechler model. */
    private EHModel model;

    /** Current mass. */
    private double mass;

    /** Reference radius of the central body attraction model (m). */
    private double referenceRadius;

    /** Central attraction coefficient (m<sup>3</sup>/s<sup>2</sup>). */
    private double mu;

    /** Un-normalized zonal coefficients. */
    private double[] ck0;

    /** Build a propagator from orbit and potential provider.
     * <p>Mass and attitude provider are set to unspecified non-null arbitrary values.</p>
     * @param initialOrbit initial orbit
     * @param provider for un-normalized zonal coefficients
     * @exception OrekitException if the zonal coefficients cannot be retrieved
     * @exception PropagationException if the mean parameters cannot be computed
     */
    public EcksteinHechlerPropagator(final Orbit initialOrbit,
                                     final UnnormalizedSphericalHarmonicsProvider provider)
        throws PropagationException , OrekitException {
        this(initialOrbit, DEFAULT_LAW, DEFAULT_MASS, provider,
                provider.onDate(initialOrbit.getDate()));
    }

    /**
     * Private helper constructor.
     * @param initialOrbit initial orbit
     * @param attitude attitude provider
     * @param mass spacecraft mass
     * @param provider for un-normalized zonal coefficients
     * @param harmonics {@code provider.onDate(initialOrbit.getDate())}
     * @exception OrekitException if the zonal coefficients cannot be retrieved
     */
    public EcksteinHechlerPropagator(final Orbit initialOrbit,
                                     final AttitudeProvider attitude,
                                     final double mass,
                                     final UnnormalizedSphericalHarmonicsProvider provider,
                                     final UnnormalizedSphericalHarmonics harmonics)
        throws OrekitException {
        this(initialOrbit, attitude, mass, provider.getAe(), provider.getMu(),
                harmonics.getUnnormalizedCnm(2, 0),
                harmonics.getUnnormalizedCnm(3, 0),
                harmonics.getUnnormalizedCnm(4, 0),
                harmonics.getUnnormalizedCnm(5, 0),
                harmonics.getUnnormalizedCnm(6, 0));
    }

    /** Build a propagator from orbit and potential.
     * <p>Mass and attitude provider are set to unspecified non-null arbitrary values.</p>
     * <p>The C<sub>n,0</sub> coefficients are the denormalized zonal coefficients, they
     * are related to both the normalized coefficients
     * <span style="text-decoration: overline">C</span><sub>n,0</sub>
     *  and the J<sub>n</sub> one as follows:</p>
     * <pre>
     *   C<sub>n,0</sub> = [(2-&delta;<sub>0,m</sub>)(2n+1)(n-m)!/(n+m)!]<sup>&frac12;</sup>
     *                      <span style="text-decoration: overline">C</span><sub>n,0</sub>
     *   C<sub>n,0</sub> = -J<sub>n</sub>
     * </pre>
     * @param initialOrbit initial orbit
     * @param referenceRadius reference radius of the Earth for the potential model (m)
     * @param mu central attraction coefficient (m<sup>3</sup>/s<sup>2</sup>)
     * @param c20 un-normalized zonal coefficient (about -1.08e-3 for Earth)
     * @param c30 un-normalized zonal coefficient (about +2.53e-6 for Earth)
     * @param c40 un-normalized zonal coefficient (about +1.62e-6 for Earth)
     * @param c50 un-normalized zonal coefficient (about +2.28e-7 for Earth)
     * @param c60 un-normalized zonal coefficient (about -5.41e-7 for Earth)
     * @exception PropagationException if the mean parameters cannot be computed
     * @see org.orekit.utils.Constants
     */
    public EcksteinHechlerPropagator(final Orbit initialOrbit,
                                     final double referenceRadius, final double mu,
                                     final double c20, final double c30, final double c40,
                                     final double c50, final double c60)
        throws PropagationException {
        this(initialOrbit, DEFAULT_LAW, DEFAULT_MASS, referenceRadius, mu, c20, c30, c40, c50, c60);
    }

    /** Build a propagator from orbit, mass and potential provider.
     * <p>Attitude law is set to an unspecified non-null arbitrary value.</p>
     * @param initialOrbit initial orbit
     * @param mass spacecraft mass
     * @param provider for un-normalized zonal coefficients
     * @exception OrekitException if the zonal coefficients cannot be retrieved
     * @exception PropagationException if the mean parameters cannot be computed
     */
    public EcksteinHechlerPropagator(final Orbit initialOrbit, final double mass,
                                     final UnnormalizedSphericalHarmonicsProvider provider)
        throws PropagationException , OrekitException {
        this(initialOrbit, DEFAULT_LAW, mass, provider, provider.onDate(initialOrbit.getDate()));
    }

    /** Build a propagator from orbit, mass and potential.
     * <p>Attitude law is set to an unspecified non-null arbitrary value.</p>
     * <p>The C<sub>n,0</sub> coefficients are the denormalized zonal coefficients, they
     * are related to both the normalized coefficients
     * <span style="text-decoration: overline">C</span><sub>n,0</sub>
     *  and the J<sub>n</sub> one as follows:</p>
     * <pre>
     *   C<sub>n,0</sub> = [(2-&delta;<sub>0,m</sub>)(2n+1)(n-m)!/(n+m)!]<sup>&frac12;</sup>
     *                      <span style="text-decoration: overline">C</span><sub>n,0</sub>
     *   C<sub>n,0</sub> = -J<sub>n</sub>
     * </pre>
     * @param initialOrbit initial orbit
     * @param mass spacecraft mass
     * @param referenceRadius reference radius of the Earth for the potential model (m)
     * @param mu central attraction coefficient (m<sup>3</sup>/s<sup>2</sup>)
     * @param c20 un-normalized zonal coefficient (about -1.08e-3 for Earth)
     * @param c30 un-normalized zonal coefficient (about +2.53e-6 for Earth)
     * @param c40 un-normalized zonal coefficient (about +1.62e-6 for Earth)
     * @param c50 un-normalized zonal coefficient (about +2.28e-7 for Earth)
     * @param c60 un-normalized zonal coefficient (about -5.41e-7 for Earth)
     * @exception PropagationException if the mean parameters cannot be computed
     */
    public EcksteinHechlerPropagator(final Orbit initialOrbit, final double mass,
                                     final double referenceRadius, final double mu,
                                     final double c20, final double c30, final double c40,
                                     final double c50, final double c60)
        throws PropagationException {
        this(initialOrbit, DEFAULT_LAW, mass, referenceRadius, mu, c20, c30, c40, c50, c60);
    }

    /** Build a propagator from orbit, attitude provider and potential provider.
     * <p>Mass is set to an unspecified non-null arbitrary value.</p>
     * @param initialOrbit initial orbit
     * @param attitudeProv attitude provider
     * @param provider for un-normalized zonal coefficients
     * @exception OrekitException if the zonal coefficients cannot be retrieved
     * @exception PropagationException if the mean parameters cannot be computed
     */
    public EcksteinHechlerPropagator(final Orbit initialOrbit,
                                     final AttitudeProvider attitudeProv,
                                     final UnnormalizedSphericalHarmonicsProvider provider)
        throws PropagationException , OrekitException {
        this(initialOrbit, attitudeProv, DEFAULT_MASS, provider,
                provider.onDate(initialOrbit.getDate()));
    }

    /** Build a propagator from orbit, attitude provider and potential.
     * <p>Mass is set to an unspecified non-null arbitrary value.</p>
     * <p>The C<sub>n,0</sub> coefficients are the denormalized zonal coefficients, they
     * are related to both the normalized coefficients
     * <span style="text-decoration: overline">C</span><sub>n,0</sub>
     *  and the J<sub>n</sub> one as follows:</p>
     * <pre>
     *   C<sub>n,0</sub> = [(2-&delta;<sub>0,m</sub>)(2n+1)(n-m)!/(n+m)!]<sup>&frac12;</sup>
     *                     <span style="text-decoration: overline">C</span><sub>n,0</sub>
     *   C<sub>n,0</sub> = -J<sub>n</sub>
     * </pre>
     * @param initialOrbit initial orbit
     * @param attitudeProv attitude provider
     * @param referenceRadius reference radius of the Earth for the potential model (m)
     * @param mu central attraction coefficient (m<sup>3</sup>/s<sup>2</sup>)
     * @param c20 un-normalized zonal coefficient (about -1.08e-3 for Earth)
     * @param c30 un-normalized zonal coefficient (about +2.53e-6 for Earth)
     * @param c40 un-normalized zonal coefficient (about +1.62e-6 for Earth)
     * @param c50 un-normalized zonal coefficient (about +2.28e-7 for Earth)
     * @param c60 un-normalized zonal coefficient (about -5.41e-7 for Earth)
     * @exception PropagationException if the mean parameters cannot be computed
     */
    public EcksteinHechlerPropagator(final Orbit initialOrbit,
                                     final AttitudeProvider attitudeProv,
                                     final double referenceRadius, final double mu,
                                     final double c20, final double c30, final double c40,
                                     final double c50, final double c60)
        throws PropagationException {
        this(initialOrbit, attitudeProv, DEFAULT_MASS, referenceRadius, mu, c20, c30, c40, c50, c60);
    }

    /** Build a propagator from orbit, attitude provider, mass and potential provider.
     * @param initialOrbit initial orbit
     * @param attitudeProv attitude provider
     * @param mass spacecraft mass
     * @param provider for un-normalized zonal coefficients
     * @exception OrekitException if the zonal coefficients cannot be retrieved
     * @exception PropagationException if the mean parameters cannot be computed
     */
    public EcksteinHechlerPropagator(final Orbit initialOrbit,
                                     final AttitudeProvider attitudeProv,
                                     final double mass,
                                     final UnnormalizedSphericalHarmonicsProvider provider)
        throws PropagationException , OrekitException {
        this(initialOrbit, attitudeProv, mass, provider,
                provider.onDate(initialOrbit.getDate()));
    }

    /** Build a propagator from orbit, attitude provider, mass and potential.
     * <p>The C<sub>n,0</sub> coefficients are the denormalized zonal coefficients, they
     * are related to both the normalized coefficients
     * <span style="text-decoration: overline">C</span><sub>n,0</sub>
     *  and the J<sub>n</sub> one as follows:</p>
     * <pre>
     *   C<sub>n,0</sub> = [(2-&delta;<sub>0,m</sub>)(2n+1)(n-m)!/(n+m)!]<sup>&frac12;</sup>
     *                      <span style="text-decoration: overline">C</span><sub>n,0</sub>
     *   C<sub>n,0</sub> = -J<sub>n</sub>
     * </pre>
     * @param initialOrbit initial orbit
     * @param attitudeProv attitude provider
     * @param mass spacecraft mass
     * @param referenceRadius reference radius of the Earth for the potential model (m)
     * @param mu central attraction coefficient (m<sup>3</sup>/s<sup>2</sup>)
     * @param c20 un-normalized zonal coefficient (about -1.08e-3 for Earth)
     * @param c30 un-normalized zonal coefficient (about +2.53e-6 for Earth)
     * @param c40 un-normalized zonal coefficient (about +1.62e-6 for Earth)
     * @param c50 un-normalized zonal coefficient (about +2.28e-7 for Earth)
     * @param c60 un-normalized zonal coefficient (about -5.41e-7 for Earth)
     * @exception PropagationException if the mean parameters cannot be computed
     */
    public EcksteinHechlerPropagator(final Orbit initialOrbit,
                                     final AttitudeProvider attitudeProv,
                                     final double mass,
                                     final double referenceRadius, final double mu,
                                     final double c20, final double c30, final double c40,
                                     final double c50, final double c60)
        throws PropagationException {

        super(attitudeProv);
        this.mass = mass;

        try {

            // store model coefficients
            this.referenceRadius = referenceRadius;
            this.mu  = mu;
            this.ck0 = new double[] {
                0.0, 0.0, c20, c30, c40, c50, c60
            };

            // compute mean parameters
            // transform into circular adapted parameters used by the Eckstein-Hechler model
            resetInitialState(new SpacecraftState(initialOrbit,
                                                  attitudeProv.getAttitude(initialOrbit,
                                                                           initialOrbit.getDate(),
                                                                           initialOrbit.getFrame()),
                                                  mass));

        } catch (OrekitException oe) {
            throw new PropagationException(oe);
        }
    }

    /** {@inheritDoc} */
    public void resetInitialState(final SpacecraftState state)
        throws PropagationException {
        super.resetInitialState(state);
        this.mass = state.getMass();
        computeMeanParameters((CircularOrbit) OrbitType.CIRCULAR.convertType(state.getOrbit()));
    }

    /** Compute mean parameters according to the Eckstein-Hechler analytical model.
     * @param osculating osculating orbit
     * @exception PropagationException if orbit goes outside of supported range
     * (trajectory inside the Brillouin sphere, too eccentric, equatorial, critical
     * inclination) or if convergence cannot be reached
     */
    private void computeMeanParameters(final CircularOrbit osculating)
        throws PropagationException {

        // sanity check
        if (osculating.getA() < referenceRadius) {
            throw new PropagationException(OrekitMessages.TRAJECTORY_INSIDE_BRILLOUIN_SPHERE,
                                           osculating.getA());
        }

        // rough initialization of the mean parameters
        EHModel current = new EHModel(osculating);

        // threshold for each parameter
        final double epsilon         = 1.0e-13;
        final double thresholdA      = epsilon * (1 + FastMath.abs(current.mean.getA()));
        final double thresholdE      = epsilon * (1 + current.mean.getE());
        final double thresholdAngles = epsilon * FastMath.PI;

        int i = 0;
        while (i++ < 100) {

            // recompute the osculating parameters from the current mean parameters
            final CircularOrbit rebuilt = current.propagateOrbit(current.mean.getDate());

            // adapted parameters residuals
            final double deltaA      = osculating.getA()  - rebuilt.getA();
            final double deltaEx     = osculating.getCircularEx() - rebuilt.getCircularEx();
            final double deltaEy     = osculating.getCircularEy() - rebuilt.getCircularEy();
            final double deltaI      = osculating.getI()  - rebuilt.getI();
            final double deltaRAAN   = MathUtils.normalizeAngle(osculating.getRightAscensionOfAscendingNode() -
                                                                rebuilt.getRightAscensionOfAscendingNode(),
                                                                0.0);
            final double deltaAlphaM = MathUtils.normalizeAngle(osculating.getAlphaM() - rebuilt.getAlphaM(), 0.0);

            // update mean parameters
            current = new EHModel(new CircularOrbit(current.mean.getA()          + deltaA,
                                                    current.mean.getCircularEx() + deltaEx,
                                                    current.mean.getCircularEy() + deltaEy,
                                                    current.mean.getI()          + deltaI,
                                                    current.mean.getRightAscensionOfAscendingNode() + deltaRAAN,
                                                    current.mean.getAlphaM()     + deltaAlphaM,
                                                    PositionAngle.MEAN,
                                                    current.mean.getFrame(),
                                                    current.mean.getDate(), mu));

            // check convergence
            if ((FastMath.abs(deltaA)      < thresholdA) &&
                (FastMath.abs(deltaEx)     < thresholdE) &&
                (FastMath.abs(deltaEy)     < thresholdE) &&
                (FastMath.abs(deltaI)      < thresholdAngles) &&
                (FastMath.abs(deltaRAAN)   < thresholdAngles) &&
                (FastMath.abs(deltaAlphaM) < thresholdAngles)) {
                model = current;
                return;
            }

        }

        throw new PropagationException(OrekitMessages.UNABLE_TO_COMPUTE_ECKSTEIN_HECHLER_MEAN_PARAMETERS, i);

    }

    /** {@inheritDoc} */
    public EHOrbit propagateOrbit(final AbsoluteDate date)
        throws PropagationException {
        return model.propagateOrbit(date);
    }

    /** Local class for Eckstein-Hechler model, with fixed mean parameters. */
    private class EHModel {

        /** Mean orbit. */
        private final CircularOrbit mean;

        // CHECKSTYLE: stop JavadocVariable check

        // preprocessed values
        private final double xnotDot;
        private final double rdpom;
        private final double rdpomp;
        private final double eps1;
        private final double eps2;
        private final double xim;
        private final double ommD;
        private final double rdl;
        private final double aMD;

        private final double kh;
        private final double kl;

        private final double ax1;
        private final double ay1;
        private final double as1;
        private final double ac2;
        private final double axy3;
        private final double as3;
        private final double ac4;
        private final double as5;
        private final double ac6;

        private final double ex1;
        private final double exx2;
        private final double exy2;
        private final double ex3;
        private final double ex4;

        private final double ey1;
        private final double eyx2;
        private final double eyy2;
        private final double ey3;
        private final double ey4;

        private final double rx1;
        private final double ry1;
        private final double r2;
        private final double r3;
        private final double rl;

        private final double iy1;
        private final double ix1;
        private final double i2;
        private final double i3;
        private final double ih;

        private final double lx1;
        private final double ly1;
        private final double l2;
        private final double l3;
        private final double ll;

        // CHECKSTYLE: resume JavadocVariable check

        /** Create a model for specified mean orbit.
         * @param mean mean orbit
         * @exception PropagationException if mean orbit is not within model supported domain
         */
        public EHModel(final CircularOrbit mean) throws PropagationException {

            this.mean = mean;

            // preliminary processing
            double q = referenceRadius / mean.getA();
            double ql = q * q;
            final double g2 = ck0[2] * ql;
            ql *= q;
            final double g3 = ck0[3] * ql;
            ql *= q;
            final double g4 = ck0[4] * ql;
            ql *= q;
            final double g5 = ck0[5] * ql;
            ql *= q;
            final double g6 = ck0[6] * ql;

            final double cosI1 = FastMath.cos(mean.getI());
            final double sinI1 = FastMath.sin(mean.getI());
            final double sinI2 = sinI1 * sinI1;
            final double sinI4 = sinI2 * sinI2;
            final double sinI6 = sinI2 * sinI4;

            if (sinI2 < 1.0e-10) {
                throw new PropagationException(OrekitMessages.ALMOST_EQUATORIAL_ORBIT,
                                               FastMath.toDegrees(mean.getI()));
            }

            if (FastMath.abs(sinI2 - 4.0 / 5.0) < 1.0e-3) {
                throw new PropagationException(OrekitMessages.ALMOST_CRITICALLY_INCLINED_ORBIT,
                                               FastMath.toDegrees(mean.getI()));
            }

            if (mean.getE() > 0.1) {
                // if 0.005 < e < 0.1 no error is triggered, but accuracy is poor
                throw new PropagationException(OrekitMessages.TOO_LARGE_ECCENTRICITY_FOR_PROPAGATION_MODEL,
                                               mean.getE());
            }

            xnotDot = FastMath.sqrt(mu / mean.getA()) / mean.getA();

            rdpom = -0.75 * g2 * (4.0 - 5.0 * sinI2);
            rdpomp = 7.5 * g4 * (1.0 - 31.0 / 8.0 * sinI2 + 49.0 / 16.0 * sinI4) -
                    13.125 * g6 * (1.0 - 8.0 * sinI2 + 129.0 / 8.0 * sinI4 - 297.0 / 32.0 * sinI6);

            q = 3.0 / (32.0 * rdpom);
            eps1 = q * g4 * sinI2 * (30.0 - 35.0 * sinI2) -
                    175.0 * q * g6 * sinI2 * (1.0 - 3.0 * sinI2 + 2.0625 * sinI4);
            q = 3.0 * sinI1 / (8.0 * rdpom);
            eps2 = q * g3 * (4.0 - 5.0 * sinI2) - q * g5 * (10.0 - 35.0 * sinI2 + 26.25 * sinI4);

            xim = mean.getI();
            ommD = cosI1 * (1.50    * g2 - 2.25 * g2 * g2 * (2.5 - 19.0 / 6.0 * sinI2) +
                            0.9375  * g4 * (7.0 * sinI2 - 4.0) +
                            3.28125 * g6 * (2.0 - 9.0 * sinI2 + 8.25 * sinI4));

            rdl = 1.0 - 1.50 * g2 * (3.0 - 4.0 * sinI2);
            aMD = rdl +
                    2.25 * g2 * g2 * (9.0 - 263.0 / 12.0 * sinI2 + 341.0 / 24.0 * sinI4) +
                    15.0 / 16.0 * g4 * (8.0 - 31.0 * sinI2 + 24.5 * sinI4) +
                    105.0 / 32.0 * g6 * (-10.0 / 3.0 + 25.0 * sinI2 - 48.75 * sinI4 + 27.5 * sinI6);

            final double qq = -1.5 * g2 / rdl;
            final double qA   = 0.75 * g2 * g2 * sinI2;
            final double qB   = 0.25 * g4 * sinI2;
            final double qC   = 105.0 / 16.0 * g6 * sinI2;
            final double qD   = -0.75 * g3 * sinI1;
            final double qE   = 3.75 * g5 * sinI1;
            kh = 0.375 / rdpom;
            kl = kh / sinI1;

            ax1 = qq * (2.0 - 3.5 * sinI2);
            ay1 = qq * (2.0 - 2.5 * sinI2);
            as1 = qD * (4.0 - 5.0 * sinI2) +
                  qE * (2.625 * sinI4 - 3.5 * sinI2 + 1.0);
            ac2 = qq * sinI2 +
                  qA * 7.0 * (2.0 - 3.0 * sinI2) +
                  qB * (15.0 - 17.5 * sinI2) +
                  qC * (3.0 * sinI2 - 1.0 - 33.0 / 16.0 * sinI4);
            axy3 = qq * 3.5 * sinI2;
            as3 = qD * 5.0 / 3.0 * sinI2 +
                  qE * 7.0 / 6.0 * sinI2 * (1.0 - 1.125 * sinI2);
            ac4 = qA * sinI2 +
                  qB * 4.375 * sinI2 +
                  qC * 0.75 * (1.1 * sinI4 - sinI2);

            as5 = qE * 21.0 / 80.0 * sinI4;

            ac6 = qC * -11.0 / 80.0 * sinI4;

            ex1 = qq * (1.0 - 1.25 * sinI2);
            exx2 = qq * 0.5 * (3.0 - 5.0 * sinI2);
            exy2 = qq * (2.0 - 1.5 * sinI2);
            ex3 = qq * 7.0 / 12.0 * sinI2;
            ex4 = qq * 17.0 / 8.0 * sinI2;

            ey1 = qq * (1.0 - 1.75 * sinI2);
            eyx2 = qq * (1.0 - 3.0 * sinI2);
            eyy2 = qq * (2.0 * sinI2 - 1.5);
            ey3 = qq * 7.0 / 12.0 * sinI2;
            ey4 = qq * 17.0 / 8.0 * sinI2;

            q  = -qq * cosI1;
            rx1 =  3.5 * q;
            ry1 = -2.5 * q;
            r2 = -0.5 * q;
            r3 =  7.0 / 6.0 * q;
            rl = g3 * cosI1 * (4.0 - 15.0 * sinI2) -
                 2.5 * g5 * cosI1 * (4.0 - 42.0 * sinI2 + 52.5 * sinI4);

            q = 0.5 * qq * sinI1 * cosI1;
            iy1 =  q;
            ix1 = -q;
            i2 =  q;
            i3 =  q * 7.0 / 3.0;
            ih = -g3 * cosI1 * (4.0 - 5.0 * sinI2) +
                 2.5 * g5 * cosI1 * (4.0 - 14.0 * sinI2 + 10.5 * sinI4);

            lx1 = qq * (7.0 - 77.0 / 8.0 * sinI2);
            ly1 = qq * (55.0 / 8.0 * sinI2 - 7.50);
            l2 = qq * (1.25 * sinI2 - 0.5);
            l3 = qq * (77.0 / 24.0 * sinI2 - 7.0 / 6.0);
            ll = g3 * (53.0 * sinI2 - 4.0 - 57.5 * sinI4) +
                 2.5 * g5 * (4.0 - 96.0 * sinI2 + 269.5 * sinI4 - 183.75 * sinI6);

        }

        /** Extrapolate an orbit up to a specific target date.
         * @param date target date for the orbit
         * @return extrapolated parameters
         * @exception PropagationException if some parameters are out of bounds
         */
        public EHOrbit propagateOrbit(final AbsoluteDate date)
            throws PropagationException {

            // keplerian evolution
            final DerivativeStructure dt =
                    new DerivativeStructure(1, 2, 0, date.durationFrom(mean.getDate()));
            final DerivativeStructure xnot = dt.multiply(xnotDot);

            // secular effects

            // eccentricity
            final DerivativeStructure x   = xnot.multiply(rdpom + rdpomp);
            final DerivativeStructure cx  = x.cos();
            final DerivativeStructure sx  = x.sin();
            final DerivativeStructure exm = cx.multiply(mean.getCircularEx()).
                                            add(sx.multiply(eps2 - (1.0 - eps1) * mean.getCircularEy()));
            final DerivativeStructure eym = sx.multiply((1.0 + eps1) * mean.getCircularEx()).
                                            add(cx.multiply(mean.getCircularEy() - eps2)).
                                            add(eps2);

            // no secular effect on inclination

            // right ascension of ascending node
            final DerivativeStructure omm =
                    new DerivativeStructure(1, 2,
                                            MathUtils.normalizeAngle(mean.getRightAscensionOfAscendingNode() + ommD * xnot.getValue(),
                                                                     FastMath.PI),
                                            ommD * xnotDot,
                                            0.0);

            // latitude argument
            final DerivativeStructure xlm =
                    new DerivativeStructure(1, 2,
                                            MathUtils.normalizeAngle(mean.getAlphaM() + aMD * xnot.getValue(), FastMath.PI),
                                            aMD * xnotDot,
                                            0.0);

            // periodical terms
            final DerivativeStructure cl1 = xlm.cos();
            final DerivativeStructure sl1 = xlm.sin();
            final DerivativeStructure cl2 = cl1.multiply(cl1).subtract(sl1.multiply(sl1));
            final DerivativeStructure sl2 = cl1.multiply(sl1).add(sl1.multiply(cl1));
            final DerivativeStructure cl3 = cl2.multiply(cl1).subtract(sl2.multiply(sl1));
            final DerivativeStructure sl3 = cl2.multiply(sl1).add(sl2.multiply(cl1));
            final DerivativeStructure cl4 = cl3.multiply(cl1).subtract(sl3.multiply(sl1));
            final DerivativeStructure sl4 = cl3.multiply(sl1).add(sl3.multiply(cl1));
            final DerivativeStructure cl5 = cl4.multiply(cl1).subtract(sl4.multiply(sl1));
            final DerivativeStructure sl5 = cl4.multiply(sl1).add(sl4.multiply(cl1));
            final DerivativeStructure cl6 = cl5.multiply(cl1).subtract(sl5.multiply(sl1));

            final DerivativeStructure qh  = eym.subtract(eps2).multiply(kh);
            final DerivativeStructure ql  = exm.multiply(kl);

            final DerivativeStructure exmCl1 = exm.multiply(cl1);
            final DerivativeStructure exmSl1 = exm.multiply(sl1);
            final DerivativeStructure eymCl1 = eym.multiply(cl1);
            final DerivativeStructure eymSl1 = eym.multiply(sl1);
            final DerivativeStructure exmCl2 = exm.multiply(cl2);
            final DerivativeStructure exmSl2 = exm.multiply(sl2);
            final DerivativeStructure eymCl2 = eym.multiply(cl2);
            final DerivativeStructure eymSl2 = eym.multiply(sl2);
            final DerivativeStructure exmCl3 = exm.multiply(cl3);
            final DerivativeStructure exmSl3 = exm.multiply(sl3);
            final DerivativeStructure eymCl3 = eym.multiply(cl3);
            final DerivativeStructure eymSl3 = eym.multiply(sl3);
            final DerivativeStructure exmCl4 = exm.multiply(cl4);
            final DerivativeStructure exmSl4 = exm.multiply(sl4);
            final DerivativeStructure eymCl4 = eym.multiply(cl4);
            final DerivativeStructure eymSl4 = eym.multiply(sl4);

            // semi major axis
            final DerivativeStructure rda = exmCl1.multiply(ax1).
                                            add(eymSl1.multiply(ay1)).
                                            add(sl1.multiply(as1)).
                                            add(cl2.multiply(ac2)).
                                            add(exmCl3.add(eymSl3).multiply(axy3)).
                                            add(sl3.multiply(as3)).
                                            add(cl4.multiply(ac4)).
                                            add(sl5.multiply(as5)).
                                            add(cl6.multiply(ac6));

            // eccentricity
            final DerivativeStructure rdex = cl1.multiply(ex1).
                                             add(exmCl2.multiply(exx2)).
                                             add(eymSl2.multiply(exy2)).
                                             add(cl3.multiply(ex3)).
                                             add(exmCl4.add(eymSl4).multiply(ex4));
            final DerivativeStructure rdey = sl1.multiply(ey1).
                                             add(exmSl2.multiply(eyx2)).
                                             add(eymCl2.multiply(eyy2)).
                                             add(sl3.multiply(ey3)).
                                             add(exmSl4.subtract(eymCl4).multiply(ey4));

            // ascending node
            final DerivativeStructure rdom = exmSl1.multiply(rx1).
                                             add(eymCl1.multiply(ry1)).
                                             add(sl2.multiply(r2)).
                                             add(eymCl3.subtract(exmSl3).multiply(r3)).
                                             add(ql.multiply(rl));

            // inclination
            final DerivativeStructure rdxi = eymSl1.multiply(iy1).
                                             add(exmCl1.multiply(ix1)).
                                             add(cl2.multiply(i2)).
                                             add(exmCl3.add(eymSl3).multiply(i3)).
                                             add(qh.multiply(ih));

            // latitude argument
            final DerivativeStructure rdxl = exmSl1.multiply(lx1).
                                             add(eymCl1.multiply(ly1)).
                                             add(sl2.multiply(l2)).
                                             add(exmSl3.subtract(eymCl3).multiply(l3)).
                                             add(ql.multiply(ll));

            // build the complete orbit
            return new EHOrbit(rda.add(1.0).multiply(mean.getA()), rdex.add(exm), rdey.add(eym),
                               rdxi.add(xim), rdom.add(omm), rdxl.add(xlm),
                               mean.getFrame(), date, mu);

        }

    }

    /** Specialization of the {@link CircularOrbit circular orbit} class,
     * taking derivatives into account for interpolation and shift.
     */
    public static class EHOrbit extends CircularOrbit {

        /** Serializable UID. */
        private static final long serialVersionUID = 20141106l;

        /** Semi-major axis (m). */
        private DerivativeStructure a;

        /** First component of the circular eccentricity vector. */
        private DerivativeStructure ex;

        /** Second component of the circular eccentricity vector. */
        private DerivativeStructure ey;

        /** Inclination (rad). */
        private DerivativeStructure i;

        /** Right Ascension of Ascending Node (rad). */
        private DerivativeStructure raan;

        /** Mean latitude argument (rad). */
        private DerivativeStructure alphaM;

        /** Creates a new instance.
         * @param a  semi-major axis (m)
         * @param ex e cos(&omega;), first component of circular eccentricity vector
         * @param ey e sin(&omega;), second component of circular eccentricity vector
         * @param i inclination (rad)
         * @param raan right ascension of ascending node (&Omega;, rad)
         * @param alphaM  M + &omega;, mean latitude argument (rad)
         * @param frame the frame in which are defined the parameters
         * (<em>must</em> be a {@link Frame#isPseudoInertial pseudo-inertial frame})
         * @param date date of the orbital parameters
         * @param mu central attraction coefficient (m<sup>3</sup>/s<sup>2</sup>)
         * @exception IllegalArgumentException if eccentricity is equal to 1 or larger or
         * if frame is not a {@link Frame#isPseudoInertial pseudo-inertial frame}
         */
        public EHOrbit(final DerivativeStructure a, final DerivativeStructure ex, final DerivativeStructure ey,
                       final DerivativeStructure i, final DerivativeStructure raan,
                       final DerivativeStructure alphaM,
                       final Frame frame, final AbsoluteDate date, final double mu)
            throws IllegalArgumentException {
            super(a.getValue(), ex.getValue(), ey.getValue(),
                  i.getValue(), raan.getValue(),
                  alphaM.getValue(), PositionAngle.MEAN,
                  toCartesian(date, a, ex, ey, i, raan, alphaM), frame, mu);
            this.a      = a;
            this.ex     = ex;
            this.ey     = ey;
            this.i      = i;
            this.raan   = raan;
            this.alphaM = alphaM;
        }

        /** {@inheritDoc} */
        public EHOrbit shiftedBy(final double dt) {
            return new EHOrbit(shift(a, dt), shift(ex,   dt), shift(ey,     dt),
                               shift(i, dt), shift(raan, dt), shift(alphaM, dt),
                               getFrame(), getDate().shiftedBy(dt), getMu());
        }

        /** Shift a {@link DerivativeStructure}.
         * @param s structure to shift
         * @param dt time shift
         * @return shifted {@link DerivativeStructure}
         */
        private DerivativeStructure shift(final DerivativeStructure s, final double dt) {
            return new DerivativeStructure(1, 2,
                                           s.getValue() + dt * (s.getPartialDerivative(1) + 0.5 * dt * s.getPartialDerivative(2)),
                                           s.getPartialDerivative(1) + dt * s.getPartialDerivative(2),
                                           s.getPartialDerivative(2));
        }

        /** {@inheritDoc}
         * <p>
         * The interpolated instance is created by polynomial Hermite interpolation
         * on circular elements, with derivatives.
         * </p>
         * <p>
         * As this methods uses the derivatives, the sample <em>must</em> contain only
         * {@link EHOrbit EHOrbit} instances, otherwise an exception will be thrown.
         * </p>
         * <p>
         * As this implementation of interpolation is polynomial, it should be used only
         * with small samples (about 10-20 points) in order to avoid <a
         * href="http://en.wikipedia.org/wiki/Runge%27s_phenomenon">Runge's phenomenon</a>
         * and numerical problems (including NaN appearing).
         * </p>
         * <p>
         * If orbit interpolation on large samples is needed, using the {@link
         * org.orekit.propagation.analytical.Ephemeris} class is a better way than using this
         * low-level interpolation. The Ephemeris class automatically handles selection of
         * a neighboring sub-sample with a predefined number of point from a large global sample
         * in a thread-safe way.
         * </p>
         * @exception OrekitException if the sample contains orbits that are not {@link EHOrbit
         * EHOrbit} instances
         */
        public EHOrbit interpolate(final AbsoluteDate date, final Collection<Orbit> sample)
            throws OrekitException {

            // set up an interpolator
            final HermiteInterpolator interpolator = new HermiteInterpolator();

            // add sample points
            AbsoluteDate previousDate = null;
            double previousRAAN   = Double.NaN;
            double previousAlphaM = Double.NaN;
            for (final Orbit orbit : sample) {
                final EHOrbit perturbed;
                try {
                    perturbed = (EHOrbit) orbit;
                } catch (ClassCastException cce) {
                    throw new OrekitException(OrekitMessages.ORBIT_TYPE_MISMATCH,
                                              orbit.getClass().getName(), EHOrbit.class.getName());
                }
                final double continuousRAAN;
                final double continuousAlphaM;
                if (previousDate == null) {
                    continuousRAAN   = perturbed.getRightAscensionOfAscendingNode();
                    continuousAlphaM = perturbed.getAlphaM();
                } else {
                    final double dt       = perturbed.getDate().durationFrom(previousDate);
                    final double keplerAM = previousAlphaM + perturbed.getKeplerianMeanMotion() * dt;
                    continuousRAAN   = MathUtils.normalizeAngle(perturbed.getRightAscensionOfAscendingNode(), previousRAAN);
                    continuousAlphaM = MathUtils.normalizeAngle(perturbed.getAlphaM(), keplerAM);
                }
                previousDate   = perturbed.getDate();
                previousRAAN   = continuousRAAN;
                previousAlphaM = continuousAlphaM;
                interpolator.addSamplePoint(perturbed.getDate().durationFrom(date),
                                            new double[] {
                                                perturbed.a.getValue(),
                                                perturbed.ex.getValue(),
                                                perturbed.ey.getValue(),
                                                perturbed.i.getValue(),
                                                continuousRAAN,
                                                continuousAlphaM
                                            },
                                            new double[] {
                                                perturbed.a.getPartialDerivative(1),
                                                perturbed.ex.getPartialDerivative(1),
                                                perturbed.ey.getPartialDerivative(1),
                                                perturbed.i.getPartialDerivative(1),
                                                perturbed.raan.getPartialDerivative(1),
                                                perturbed.alphaM.getPartialDerivative(1)
                                            },
                                            new double[] {
                                                perturbed.a.getPartialDerivative(2),
                                                perturbed.ex.getPartialDerivative(2),
                                                perturbed.ey.getPartialDerivative(2),
                                                perturbed.i.getPartialDerivative(2),
                                                perturbed.raan.getPartialDerivative(2),
                                                perturbed.alphaM.getPartialDerivative(2)
                                            });
            }

            // interpolate
            final DerivativeStructure[] interpolated = interpolator.value(new DerivativeStructure(1, 2, 0, 0.0));

            // build a new interpolated instance
            return new EHOrbit(interpolated[0], interpolated[1], interpolated[2],
                               interpolated[3], interpolated[4], interpolated[5],
                               getFrame(), date, getMu());

        }

        /** Convert circular parameters <em>with derivatives</em> to Cartesian coordinates.
         * @param date date of the orbital parameters
         * @param a  semi-major axis (m)
         * @param ex e cos(Ω), first component of circular eccentricity vector
         * @param ey e sin(Ω), second component of circular eccentricity vector
         * @param i inclination (rad)
         * @param raan right ascension of ascending node (Ω, rad)
         * @param alphaM  mean latitude argument (rad)
         * @return Cartesian coordinates consistent with values and derivatives
         */
        private static TimeStampedPVCoordinates toCartesian(final AbsoluteDate date,      final DerivativeStructure a,
                                                            final DerivativeStructure ex, final DerivativeStructure ey,
                                                            final DerivativeStructure i,  final DerivativeStructure raan,
                                                            final DerivativeStructure alphaM) {

            // evaluate coordinates in the orbit canonical reference frame
            final DerivativeStructure cosOmega = raan.cos();
            final DerivativeStructure sinOmega = raan.sin();
            final DerivativeStructure cosI     = i.cos();
            final DerivativeStructure sinI     = i.sin();
            final DerivativeStructure alphaE   = meanToEccentric(alphaM, ex, ey);
            final DerivativeStructure cosAE    = alphaE.cos();
            final DerivativeStructure sinAE    = alphaE.sin();
            final DerivativeStructure ex2      = ex.multiply(ex);
            final DerivativeStructure ey2      = ey.multiply(ey);
            final DerivativeStructure exy      = ex.multiply(ey);
            final DerivativeStructure q        = ex2.add(ey2).subtract(1).negate().sqrt();
            final DerivativeStructure beta     = q.add(1).reciprocal();
            final DerivativeStructure bx2      = beta.multiply(ex2);
            final DerivativeStructure by2      = beta.multiply(ey2);
            final DerivativeStructure bxy      = beta.multiply(exy);
            final DerivativeStructure u        = bxy.multiply(sinAE).subtract(ex.add(by2.subtract(1).multiply(cosAE)));
            final DerivativeStructure v        = bxy.multiply(cosAE).subtract(ey.add(bx2.subtract(1).multiply(sinAE)));
            final DerivativeStructure x        = a.multiply(u);
            final DerivativeStructure y        = a.multiply(v);

            // canonical orbit reference frame
            final FieldVector3D<DerivativeStructure> p =
                    new FieldVector3D<DerivativeStructure>(x.multiply(cosOmega).subtract(y.multiply(cosI.multiply(sinOmega))),
                                                           x.multiply(sinOmega).add(y.multiply(cosI.multiply(cosOmega))),
                                                           y.multiply(sinI));

            // dispatch derivatives
            final Vector3D p0 = new Vector3D(p.getX().getValue(),
                                             p.getY().getValue(),
                                             p.getZ().getValue());
            final Vector3D p1 = new Vector3D(p.getX().getPartialDerivative(1),
                                             p.getY().getPartialDerivative(1),
                                             p.getZ().getPartialDerivative(1));
            final Vector3D p2 = new Vector3D(p.getX().getPartialDerivative(2),
                                             p.getY().getPartialDerivative(2),
                                             p.getZ().getPartialDerivative(2));
            return new TimeStampedPVCoordinates(date, p0, p1, p2);

        }

        /** Computes the eccentric latitude argument from the mean latitude argument.
         * @param alphaM = M + Ω mean latitude argument (rad)
         * @param ex e cos(Ω), first component of circular eccentricity vector
         * @param ey e sin(Ω), second component of circular eccentricity vector
         * @return the eccentric latitude argument.
         */
        private static DerivativeStructure meanToEccentric(final DerivativeStructure alphaM,
                                                           final DerivativeStructure ex,
                                                           final DerivativeStructure ey) {
            // Generalization of Kepler equation to circular parameters
            // with alphaE = PA + E and
            //      alphaM = PA + M = alphaE - ex.sin(alphaE) + ey.cos(alphaE)
            DerivativeStructure alphaE        = alphaM;
            DerivativeStructure shift         = alphaM.getField().getZero();
            DerivativeStructure alphaEMalphaM = alphaM.getField().getZero();
            DerivativeStructure cosAlphaE     = alphaE.cos();
            DerivativeStructure sinAlphaE     = alphaE.sin();
            int iter = 0;
            do {
                final DerivativeStructure f2 = ex.multiply(sinAlphaE).subtract(ey.multiply(cosAlphaE));
                final DerivativeStructure f1 = alphaM.getField().getOne().subtract(ex.multiply(cosAlphaE)).subtract(ey.multiply(sinAlphaE));
                final DerivativeStructure f0 = alphaEMalphaM.subtract(f2);

                final DerivativeStructure f12 = f1.multiply(2);
                shift = f0.multiply(f12).divide(f1.multiply(f12).subtract(f0.multiply(f2)));

                alphaEMalphaM  = alphaEMalphaM.subtract(shift);
                alphaE         = alphaM.add(alphaEMalphaM);
                cosAlphaE      = alphaE.cos();
                sinAlphaE      = alphaE.sin();

            } while ((++iter < 50) && (FastMath.abs(shift.getValue()) > 1.0e-12));

            return alphaE;

        }

        /** Replace the instance with a data transfer object for serialization.
         * @return data transfer object that will be serialized
         */
        private Object writeReplace() {
            return new DTO(this);
        }

        /** Internal class used only for serialization. */
        private static class DTO implements Serializable {

            /** Serializable UID. */
            private static final long serialVersionUID = 20141111L;

            /** Double values. */
            private double[] d;

            /** Frame in which are defined the orbital parameters. */
            private final Frame frame;

            /** Simple constructor.
             * @param orbit instance to serialize
             */
            private DTO(final EHOrbit orbit) {

                final AbsoluteDate date = orbit.getDate();

                // decompose date
                final double epoch  = FastMath.floor(date.durationFrom(AbsoluteDate.J2000_EPOCH));
                final double offset = date.durationFrom(AbsoluteDate.J2000_EPOCH.shiftedBy(epoch));

                d = new double[] {
                    epoch, offset, orbit.getMu(),
                    orbit.a.getValue(),      orbit.a.getPartialDerivative(1),      orbit.a.getPartialDerivative(2),
                    orbit.ex.getValue(),     orbit.ex.getPartialDerivative(1),     orbit.ex.getPartialDerivative(2),
                    orbit.ey.getValue(),     orbit.ey.getPartialDerivative(1),     orbit.ey.getPartialDerivative(2),
                    orbit.i.getValue(),      orbit.i.getPartialDerivative(1),      orbit.i.getPartialDerivative(2),
                    orbit.raan.getValue(),   orbit.raan.getPartialDerivative(1),   orbit.raan.getPartialDerivative(2),
                    orbit.alphaM.getValue(), orbit.alphaM.getPartialDerivative(1), orbit.alphaM.getPartialDerivative(2)
                };

                frame = orbit.getFrame();

            }

            /** Replace the deserialized data transfer object with a {@link EHOrbit}.
             * @return replacement {@link EHOrbit}
             */
            private Object readResolve() {
                return new EHOrbit(new DerivativeStructure(1, 2,  d[3],  d[4],  d[5]),
                                   new DerivativeStructure(1, 2,  d[6],  d[7],  d[8]),
                                   new DerivativeStructure(1, 2,  d[9], d[10], d[11]),
                                   new DerivativeStructure(1, 2, d[12], d[13], d[14]),
                                   new DerivativeStructure(1, 2, d[15], d[16], d[17]),
                                   new DerivativeStructure(1, 2, d[18], d[19], d[20]),
                                   frame, AbsoluteDate.J2000_EPOCH.shiftedBy(d[0]).shiftedBy(d[1]), d[2]);
            }

        }

    }

    /** {@inheritDoc} */
    protected double getMass(final AbsoluteDate date) {
        return mass;
    }

}
