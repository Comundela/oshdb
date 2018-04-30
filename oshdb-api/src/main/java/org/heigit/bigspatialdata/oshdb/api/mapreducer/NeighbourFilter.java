package org.heigit.bigspatialdata.oshdb.api.mapreducer;

import com.vividsolutions.jts.geom.Envelope;
import com.vividsolutions.jts.geom.Geometry;
import org.heigit.bigspatialdata.oshdb.api.db.OSHDBJdbc;
import org.heigit.bigspatialdata.oshdb.api.generic.function.SerializableFunction;
import org.heigit.bigspatialdata.oshdb.api.generic.function.SerializableFunctionWithException;
import org.heigit.bigspatialdata.oshdb.api.generic.function.SerializablePredicate;
import org.heigit.bigspatialdata.oshdb.api.object.OSMEntitySnapshot;
import org.heigit.bigspatialdata.oshdb.util.OSHDBBoundingBox;
import org.heigit.bigspatialdata.oshdb.util.geometry.OSHDBGeometryBuilder;
import org.heigit.bigspatialdata.oshdb.util.tagInterpreter.DefaultTagInterpreter;

/**
 * Finds objects with a certain tag nearby the input object
 *
 **/
public class NeighbourFilter implements SerializableFunction<OSMEntitySnapshot, Boolean> {

    private OSHDBJdbc oshdb;
    private Double distanceInMeter;
    private SerializableFunctionWithException<MapReducer, Boolean> MapReduce;

    public NeighbourFilter(OSHDBJdbc oshdb, Double distanceInMeter, SerializableFunctionWithException<MapReducer, Boolean> MapReduce) {
        this.oshdb = oshdb;
        this.distanceInMeter = distanceInMeter;
        this.MapReduce = MapReduce;
    }

    public Boolean apply(OSMEntitySnapshot snapshot) {

        DefaultTagInterpreter defaultTagInterpreter;
        boolean result = false;

        try {

            // Convert from geographic coordinates to projected CRS
                /* ---- Not working -----
                // Source CRS (WGS 84)
                CoordinateReferenceSystem sourceCRS = CRS.decode("EPSG:1981", true); // WGS 84
                // Set target SRS (Pseudo mercado)
                CoordinateReferenceSystem targetCRS = CRS.decode("EPSG:3857", true);
                // Transformation object
                MathTransform transform = CRS.findMathTransform(sourceCRS, targetCRS, false);

                // Create circle polygon around centroid with buffer as radius
                //Geometry geom = JTS.transform(snapshot.getGeometry(), transform);
                */

            // Get geometry of feature
            defaultTagInterpreter = new DefaultTagInterpreter(oshdb.getConnection());
            Geometry geom = OSHDBGeometryBuilder.getGeometry(snapshot.getEntity(),
                    snapshot.getTimestamp(),
                    defaultTagInterpreter);

            // Buffer geometry
            Envelope geomBuffered = geom.buffer(distanceInMeter).getEnvelopeInternal();

            // Get min/max coordinates of bounding box
            double minLat = geomBuffered.getMinY();
            double maxLat = geomBuffered.getMaxY();
            double minLon = geomBuffered.getMinX();
            double maxLon = geomBuffered.getMaxX();

            System.out.println(snapshot.getEntity().getId());
            System.out.println(snapshot.getTimestamp());

            MapReducer SubMapReducer = OSMEntitySnapshotView.on(oshdb)
                .keytables(oshdb)
                .areaOfInterest(new OSHDBBoundingBox(minLon, minLat, maxLon, maxLat))
                .timestamps(snapshot.getTimestamp().toString())
                .filter((snapshot2) -> {
                    try {
                        // Get geometry of object and convert it to projected CRS
                        //Geometry geom2 = JTS.transform(snapshot2.getGeometry(), transform);
                        Geometry geom2 = OSHDBGeometryBuilder.getGeometry(snapshot2.getEntity(),
                                snapshot2.getTimestamp(),
                                defaultTagInterpreter);

                        // Check if geometry is within buffer distance
                        return geom2.isWithinDistance(geom, distanceInMeter);

                    } catch (Exception e) {
                        e.printStackTrace();
                        return false;
                    }
                });

            // Apply mapReducer given by user
            result = MapReduce.apply(SubMapReducer);
            System.out.println(SubMapReducer.where("natural", "tree").count());

        } catch (Exception e) {
            e.printStackTrace();
        }

        return result;

    }
}