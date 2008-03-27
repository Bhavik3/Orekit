package fr.cs.orekit.models.bodies;

import org.apache.commons.math.geometry.Vector3D;
import fr.cs.orekit.bodies.ThirdBody;
import fr.cs.orekit.errors.OrekitException;
import fr.cs.orekit.frames.Frame;
import fr.cs.orekit.frames.Transform;
import fr.cs.orekit.time.AbsoluteDate;

/** Sun model.
 * The position model is the Newcomb theory.
 * @author E. Delente
 */

public class Sun extends ThirdBody {

    /** Serializable UID. */
    private static final long serialVersionUID = 6780721457181916297L;

    /** Reference date. */
    private static final AbsoluteDate reference =
        new AbsoluteDate(AbsoluteDate.FiftiesEpoch, 864000000.0);

    /** Transform from Veis1950 to J2000. */
    private final Transform transform;

    /** Simple constructor.
     */
    public Sun() {
        super(6.96e8, 1.32712440e20);
        Transform t;
        try {
            t  =
                Frame.getReferenceFrame(Frame.VEIS1950, reference).getTransformTo(Frame.getJ2000(), reference);
        } catch (OrekitException e) {
            // should not happen
            t = new Transform();
        }
        transform = t;
    }

    /** Gets the position of the Sun in the selected Frame.
     * <p>The position model is the Newcomb theory
     * as used in the MSLIB library.</p>
     * @param date date
     * @param frame the frame where to define the position
     * @return position of the sun (m) in the J2000 Frame
     * @throws OrekitException if a frame conversion cannot be computed
     */
    public Vector3D getPosition(AbsoluteDate date, Frame frame) throws OrekitException {


        double t = date.minus(reference) / 86400.0;
        double f = Math.toRadians(225.768 + 13.2293505 * t);
        double d = Math.toRadians(11.786 + 12.190749 * t);
        double xlp = Math.toRadians(134.003 + 0.9856 * t);
        double g = Math.toRadians(282.551 + 0.000047 * t);
        double e = Math.toRadians(23.44223 - 3.5626E-07 * t);
        double ce = Math.cos(e);
        double se = Math.sin(e);
        double rot = 0.6119022e-6 * date.minus(AbsoluteDate.FiftiesEpoch) / 86400.0;
        double cr = Math.cos(rot);
        double sr = Math.sin(rot);

        // Newcomb's theory
        double cl = 99972.0 * Math.cos(xlp + g) + 1671.0 * Math.cos(xlp + xlp + g)
        - 1678.0 * Math.cos(g);
        cl = cl + 32.0 * Math.cos(3.0 * xlp + g) + Math.cos(4.0 * xlp + g) + 2.0
        * Math.cos(xlp + d + g);
        cl = cl - 4.0 * Math.cos(g - xlp) - 2.0 * Math.cos(xlp - d + g) + 4.0
        * Math.cos(f - d);
        cl = cl - 4.0 * Math.cos(xlp + xlp - f + d + g + g);
        cl = cl * 1.E-05;

        double sl = 99972.0 * Math.sin(xlp + g) + 1671.0 * Math.sin(xlp + xlp + g)
        - 1678.0 * Math.sin(g);
        sl = sl + 32.0 * Math.sin(3.0 * xlp + g) + Math.sin(4.0 * xlp + g) + 2.0
        * Math.sin(xlp + d + g);
        sl = sl - 4.0 * Math.sin(g - xlp) - 2.0 * Math.sin(xlp - d + g) + 4.0
        * Math.sin(f - d);
        sl = sl - 4.0 * Math.sin(xlp + xlp - f + d + g + g);
        sl = sl * 1.E-05;

        double q = Math.sqrt(cl * cl + sl * sl);
        double sx = cl / q;
        double sy = sl * ce / q;
        Vector3D centralSun =
            transform.transformVector(new Vector3D(sx * cr + sy * sr,
                                                   sy * cr - sx * sr,
                                                   sl * se / q));
        double dasr = 1672.2 * Math.cos(xlp) + 28.0 * Math.cos(xlp + xlp)
        - 0.35 * Math.cos(d);

        Vector3D posInJ2000 =  new Vector3D(1000.0 * 149597870.0 / (1.0 + 1.E-05 * dasr), centralSun);

        return Frame.getJ2000().getTransformTo(frame, date).transformPosition(posInJ2000);
    }

}