package org.heigit.bigspatialdata.oshdb.util.celliterator;

import static junit.framework.TestCase.fail;
import static org.heigit.bigspatialdata.oshdb.util.geometry.helpers.TimestampParser.toOSHDBTimestamp;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertTrue;

import com.vividsolutions.jts.geom.Coordinate;
import com.vividsolutions.jts.geom.CoordinateSequence;
import com.vividsolutions.jts.geom.Geometry;
import com.vividsolutions.jts.geom.GeometryCollection;
import com.vividsolutions.jts.geom.GeometryFactory;
import com.vividsolutions.jts.geom.LineString;
import com.vividsolutions.jts.geom.MultiPolygon;
import com.vividsolutions.jts.geom.Polygon;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.TreeMap;
import java.util.stream.Collectors;
import org.heigit.bigspatialdata.oshdb.grid.GridOSHRelations;
import org.heigit.bigspatialdata.oshdb.osh.OSHNode;
import org.heigit.bigspatialdata.oshdb.osh.OSHRelation;
import org.heigit.bigspatialdata.oshdb.osh.OSHWay;
import org.heigit.bigspatialdata.oshdb.osm.OSMEntity;
import org.heigit.bigspatialdata.oshdb.osm.OSMNode;
import org.heigit.bigspatialdata.oshdb.osm.OSMRelation;
import org.heigit.bigspatialdata.oshdb.osm.OSMType;
import org.heigit.bigspatialdata.oshdb.osm.OSMWay;
import org.heigit.bigspatialdata.oshdb.util.OSHDBBoundingBox;
import org.heigit.bigspatialdata.oshdb.util.OSHDBTimestamp;
import org.heigit.bigspatialdata.oshdb.util.celliterator.CellIterator.IterateAllEntry;
import org.heigit.bigspatialdata.oshdb.util.celliterator.CellIterator.IterateByTimestampEntry;
import org.heigit.bigspatialdata.oshdb.util.geometry.OSHDBGeometryBuilder;
import org.heigit.bigspatialdata.oshdb.util.geometry.helpers.OSMXmlReaderTagInterpreter;
import org.heigit.bigspatialdata.oshdb.util.tagInterpreter.TagInterpreter;
import org.heigit.bigspatialdata.oshdb.util.test.OSMXmlReader;
import org.heigit.bigspatialdata.oshdb.util.time.OSHDBTimestamps;
import org.junit.Test;

public class IterateByContributionRelationsTest {
  private GridOSHRelations oshdbDataGridCell;
  private final OSMXmlReader osmXmlTestData = new OSMXmlReader();
  TagInterpreter areaDecider;
  private final double DELTA = 1E-6;

  public IterateByContributionRelationsTest() throws IOException {
    osmXmlTestData.add("./src/test/resources/different-timestamps/polygon.osm");
    areaDecider = new OSMXmlReaderTagInterpreter(osmXmlTestData);
    Map<Long, OSHNode> oshNodes = new TreeMap<>();
    for (Entry<Long, Collection<OSMNode>> entry : osmXmlTestData.nodes().asMap().entrySet()) {
      oshNodes.put(entry.getKey(), OSHNode.build(new ArrayList<>(entry.getValue())));
    }
    Map<Long, OSHWay> oshWays = new TreeMap<>();
    for (Entry<Long, Collection<OSMWay>> entry : osmXmlTestData.ways().asMap().entrySet()) {
      Collection<OSMWay> wayVersions = entry.getValue();
      oshWays.put(entry.getKey(), OSHWay.build(new ArrayList<>(wayVersions),
          wayVersions.stream().flatMap(osmWay ->
              Arrays.stream(osmWay.getRefs()).map(ref -> oshNodes.get(ref.getId()))
          ).collect(Collectors.toSet())
      ));
    }
    List<OSHRelation> oshRelations = new ArrayList<>();
    for (Entry<Long, Collection<OSMRelation>> entry : osmXmlTestData.relations().asMap().entrySet()) {
      Collection<OSMRelation> relationVersions = entry.getValue();
      oshRelations.add(OSHRelation.build(new ArrayList<>(relationVersions),
          relationVersions.stream().flatMap(osmRelation ->
              Arrays.stream(osmRelation.getMembers())
                  .filter(member -> member.getType() == OSMType.NODE)
                  .map(member -> oshNodes.get(member.getId()))
          ).collect(Collectors.toSet()),
          relationVersions.stream().flatMap(osmRelation ->
              Arrays.stream(osmRelation.getMembers())
                  .filter(member -> member.getType() == OSMType.WAY)
                  .map(member -> oshWays.get(member.getId()))
          ).collect(Collectors.toSet())
      ));
    }
    oshdbDataGridCell = GridOSHRelations.compact(0, 0, 0, 0, 0, 0, oshRelations);
  }

  @Test
  public void testGeometryChange() {
    // relation: creation and two geometry changes, but no tag changes
    // relation getting more ways, one disappears
    List<IterateAllEntry> result = (new CellIterator(
        new OSHDBTimestamps(
            "2000-01-01T00:00:00Z",
            "2020-01-01T00:00:00Z"
        ).get(),
        new OSHDBBoundingBox(-180,-90, 180, 90),
        areaDecider,
        oshEntity -> oshEntity.getId() == 500,
        osmEntity -> true,
        false
    )).iterateByContribution(
        oshdbDataGridCell
    ).collect(Collectors.toList());

    assertEquals(3, result.size());
    assertEquals(
        EnumSet.of(ContributionType.CREATION),
        result.get(0).activities.get()
    );
    assertEquals(
        EnumSet.of(ContributionType.GEOMETRY_CHANGE),
        result.get(1).activities.get()
    );
    assertEquals(
        EnumSet.of(ContributionType.GEOMETRY_CHANGE),
        result.get(2).activities.get()
    );
    assertEquals(300, result.get(0).changeset);
    Geometry geom = result.get(0).geometry.get();
    assertTrue(geom instanceof MultiPolygon);
    Geometry geom3 = result.get(1).geometry.get();
    assertTrue(geom3 instanceof MultiPolygon);
    Geometry geom4 = result.get(2).geometry.get();
    assertTrue(geom4 instanceof MultiPolygon);
  }

  @Test
  public void testVisibleChange() {
    // relation: creation and 2 visible changes, but no geometry and no tag changes
    // relation visible tag changed
    List<IterateAllEntry> result = (new CellIterator(
        new OSHDBTimestamps(
            "2000-01-01T00:00:00Z",
            "2020-01-01T00:00:00Z"
        ).get(),
        new OSHDBBoundingBox(-180,-90, 180, 90),
        areaDecider,
        oshEntity -> oshEntity.getId() == 501,
        osmEntity -> true,
        false
    )).iterateByContribution(
        oshdbDataGridCell
    ).collect(Collectors.toList());

    assertEquals(3, result.size());
    assertEquals(
        EnumSet.of(ContributionType.CREATION),
        result.get(0).activities.get()
    );
    assertEquals(
        EnumSet.of(ContributionType.DELETION),
        result.get(1).activities.get()
    );
    assertEquals(
        EnumSet.of(ContributionType.CREATION),
        result.get(2).activities.get()
    );
    assertEquals(303, result.get(0).changeset);
  }

  @Test
  public void testWaysNotExistent() {
    // relation with two ways, both missing
    try {
      List<IterateAllEntry> result = (new CellIterator(
          new OSHDBTimestamps(
              "2000-01-01T00:00:00Z",
              "2020-01-01T00:00:00Z"
          ).get(),
          new OSHDBBoundingBox(-180,-90, 180, 90),
          areaDecider,
          oshEntity -> oshEntity.getId() == 502,
          osmEntity -> true,
          false
      )).iterateByContribution(
          oshdbDataGridCell
      ).collect(Collectors.toList());
    }
    catch(Exception e){
      e.printStackTrace();
      fail("Should not have thrown any exception");
    }
  }

  @Test
  public void testTagChange() {
    // relation: creation and two tag changes
    List<IterateAllEntry> result = (new CellIterator(
        new OSHDBTimestamps(
            "2000-01-01T00:00:00Z",
            "2020-01-01T00:00:00Z"
        ).get(),
        new OSHDBBoundingBox(-180,-90, 180, 90),
        areaDecider,
        oshEntity -> oshEntity.getId() == 503,
        osmEntity -> true,
        false
    )).iterateByContribution(
        oshdbDataGridCell
    ).collect(Collectors.toList());

    assertEquals(3, result.size());
    assertEquals(
        EnumSet.of(ContributionType.CREATION),
        result.get(0).activities.get()
    );
    assertEquals(
        EnumSet.of(ContributionType.TAG_CHANGE),
        result.get(1).activities.get()
    );
    assertEquals(
        EnumSet.of(ContributionType.TAG_CHANGE),
        result.get(2).activities.get()
    );
    assertEquals(307, result.get(0).changeset);
  }

  @Test
  public void testGeometryChangeOfNodeRefsInWays() {
    // relation: creation and geometry change of ways, but no tag changes
    // relation, way 109 -inner- and 110 -outer- ways changed node refs-
    List<IterateAllEntry> result = (new CellIterator(
        new OSHDBTimestamps(
            "2000-01-01T00:00:00Z",
            "2020-01-01T00:00:00Z"
        ).get(),
        new OSHDBBoundingBox(-180,-90, 180, 90),
        areaDecider,
        oshEntity -> oshEntity.getId() == 504,
        osmEntity -> true,
        false
    )).iterateByContribution(
        oshdbDataGridCell
    ).collect(Collectors.toList());

    assertEquals(8, result.size());
    assertEquals(
        EnumSet.of(ContributionType.CREATION),
        result.get(0).activities.get()
    );
    assertEquals(
        EnumSet.of(ContributionType.GEOMETRY_CHANGE),
        result.get(1).activities.get()
    );

    assertEquals(310, result.get(0).changeset);

    assertEquals(null, result.get(0).previousGeometry.get());
    Geometry geom = result.get(0).geometry.get();
    assertTrue(geom instanceof Polygon);
    Geometry geom3 = result.get(1).geometry.get();
    assertTrue(geom3 instanceof Polygon);

    assertNotEquals(result.get(1).geometry.get(), result.get(1).previousGeometry.get());
    assertEquals(result.get(2).geometry.get(), result.get(2).previousGeometry.get());
  }

  @Test
  public void testGeometryChangeOfNodeCoordinatesInWay() {
    // relation: creation
    // relation, way 112 -outer- changed node coordinates
    List<IterateAllEntry> result = (new CellIterator(
        new OSHDBTimestamps(
            "2000-01-01T00:00:00Z",
            "2020-01-01T00:00:00Z"
        ).get(),
        new OSHDBBoundingBox(-180,-90, 180, 90),
        areaDecider,
        oshEntity -> oshEntity.getId() == 505,
        osmEntity -> true,
        false
    )).iterateByContribution(
        oshdbDataGridCell
    ).collect(Collectors.toList());

    assertEquals(3, result.size());
    assertEquals(
        EnumSet.of(ContributionType.CREATION),
        result.get(0).activities.get()
    );
    assertEquals(
        EnumSet.of(ContributionType.GEOMETRY_CHANGE),
        result.get(1).activities.get()
    );
    result.iterator().forEachRemaining(k->System.out.println(k.activities.get()));
    assertEquals(312, result.get(0).changeset);

    assertEquals(null, result.get(0).previousGeometry.get());
    Geometry geom = result.get(0).geometry.get();
    assertTrue(geom instanceof Polygon);
    Geometry geom3 = result.get(1).geometry.get();
    assertTrue(geom3 instanceof Polygon);

    assertNotEquals(result.get(1).geometry.get(), result.get(1).previousGeometry.get());
    assertNotEquals(result.get(2).geometry.get(), result.get(2).previousGeometry.get());
  }

  @Test
  public void testGeometryChangeOfNodeCoordinatesInRelationAndWay() {
    // relation: creation
    // relation, with node members, nodes and nodes in way changed coordinates
    List<IterateAllEntry> result = (new CellIterator(
        new OSHDBTimestamps(
            "2000-01-01T00:00:00Z",
            "2020-01-01T00:00:00Z"
        ).get(),
        new OSHDBBoundingBox(-180,-90, 180, 90),
        areaDecider,
        oshEntity -> oshEntity.getId() == 506,
        osmEntity -> true,
        false
    )).iterateByContribution(
        oshdbDataGridCell
    ).collect(Collectors.toList());

    assertEquals(3, result.size());
    assertEquals(
        EnumSet.of(ContributionType.CREATION),
        result.get(0).activities.get()
    );
    assertEquals(
        EnumSet.of(ContributionType.GEOMETRY_CHANGE),
        result.get(1).activities.get()
    );

    assertEquals(313, result.get(0).changeset);

    assertEquals(null, result.get(0).previousGeometry.get());
    Geometry geom = result.get(0).geometry.get();
    assertTrue(geom instanceof Polygon);
    Geometry geom3 = result.get(1).geometry.get();
    assertTrue(geom3 instanceof Polygon);

    assertNotEquals(result.get(1).geometry.get(), result.get(1).previousGeometry.get());
    assertEquals(result.get(2).geometry.get(), result.get(2).previousGeometry.get());
  }

  @Test
  public void testGeometryCollection() {
    // relation, not valid, should be geometryCollection
    List<IterateAllEntry> result = (new CellIterator(
        new OSHDBTimestamps(
            "2000-01-01T00:00:00Z",
            "2020-01-01T00:00:00Z"
        ).get(),
        new OSHDBBoundingBox(-180,-90, 180, 90),
        areaDecider,
        oshEntity -> oshEntity.getId() == 507,
        osmEntity -> true,
        false
    )).iterateByContribution(
        oshdbDataGridCell
    ).collect(Collectors.toList());

    assertEquals(3, result.size());
    assertEquals(
        EnumSet.of(ContributionType.CREATION),
        result.get(0).activities.get()
    );

    assertEquals(314, result.get(0).changeset);
    assertEquals(null, result.get(0).previousGeometry.get());
    Geometry geom = result.get(0).geometry.get();
    assertTrue(geom instanceof GeometryCollection);
    Geometry geom1 = result.get(1).geometry.get();
    assertTrue(geom1 instanceof GeometryCollection);
    Geometry geom2 = result.get(2).geometry.get();
    assertTrue(geom2 instanceof GeometryCollection);
  }

  @Test
  public void testNodesOfWaysNotExistent() {
    // relation 2 way members nodes do not exist
    try {
      List<IterateAllEntry> result = (new CellIterator(
          new OSHDBTimestamps(
              "2000-01-01T00:00:00Z",
              "2020-01-01T00:00:00Z"
          ).get(),
          new OSHDBBoundingBox(-180,-90, 180, 90),
          areaDecider,
          oshEntity -> oshEntity.getId() == 508,
          osmEntity -> true,
          false
      )).iterateByContribution(
          oshdbDataGridCell
      ).collect(Collectors.toList());
    }
    catch(Exception e){
      e.printStackTrace();
      fail("Should not have thrown any exception");
    }
  }

  @Test
  public void testVisibleChangeOfNodeInWay() {
    // relation, way member: node 52 changes visible tag
    List<IterateAllEntry> result = (new CellIterator(
        new OSHDBTimestamps(
            "2000-01-01T00:00:00Z",
            "2020-01-01T00:00:00Z"
        ).get(),
        new OSHDBBoundingBox(-180,-90, 180, 90),
        areaDecider,
        oshEntity -> oshEntity.getId() == 509,
        osmEntity -> true,
        false
    )).iterateByContribution(
        oshdbDataGridCell
    ).collect(Collectors.toList());

    assertEquals(5, result.size());
    assertEquals(
        EnumSet.of(ContributionType.CREATION),
        result.get(0).activities.get()
    );
    assertEquals(
        EnumSet.of(ContributionType.GEOMETRY_CHANGE),
        result.get(1).activities.get()
    );
    assertEquals(
        EnumSet.of(ContributionType.GEOMETRY_CHANGE),
        result.get(2).activities.get()
    );
    assertEquals(
        EnumSet.of(ContributionType.GEOMETRY_CHANGE),
        result.get(3).activities.get()
    );
    assertEquals(
        EnumSet.of(ContributionType.GEOMETRY_CHANGE),
        result.get(4).activities.get()
    );

    assertEquals(316, result.get(0).changeset);

    assertEquals(null, result.get(0).previousGeometry.get());
    Geometry geom = result.get(0).geometry.get();
    assertTrue(geom instanceof Polygon);
    Geometry geom2 = result.get(1).geometry.get();
    assertTrue(geom2 instanceof Polygon);
    Geometry geom3 = result.get(2).geometry.get();
    assertTrue(geom3 instanceof Polygon);
    Geometry geom4 = result.get(3).geometry.get();
    assertTrue(geom4 instanceof Polygon);
    Geometry geom5 = result.get(4).geometry.get();
    assertTrue(geom5 instanceof Polygon);

    assertNotEquals(result.get(1).geometry.get(), result.get(1).previousGeometry.get());
    assertNotEquals(result.get(2).geometry.get(), result.get(2).previousGeometry.get());
  }

  @Test
  public void testTagChangeOfNodeInWay() {
    // relation, way member: node 53 changes tags-
    List<IterateAllEntry> result = (new CellIterator(
        new OSHDBTimestamps(
            "2000-01-01T00:00:00Z",
            "2020-01-01T00:00:00Z"
        ).get(),
        new OSHDBBoundingBox(-180,-90, 180, 90),
        areaDecider,
        oshEntity -> oshEntity.getId() == 510,
        osmEntity -> true,
        false
    )).iterateByContribution(
        oshdbDataGridCell
    ).collect(Collectors.toList());

    assertEquals(4, result.size());
    assertEquals(
        EnumSet.of(ContributionType.CREATION),
        result.get(0).activities.get()
    );

    assertEquals(317, result.get(0).changeset);
    assertEquals(null, result.get(0).previousGeometry.get());
  }

  @Test
  public void testVisibleChangeOfWay() {
    // relation, way member: way 119 changes visible tag-
    List<IterateAllEntry> result = (new CellIterator(
        new OSHDBTimestamps(
            "2000-01-01T00:00:00Z",
            "2020-01-01T00:00:00Z"
        ).get(),
        new OSHDBBoundingBox(-180,-90, 180, 90),
        areaDecider,
        oshEntity -> oshEntity.getId() == 511,
        osmEntity -> true,
        false
    )).iterateByContribution(
        oshdbDataGridCell
    ).collect(Collectors.toList());

    assertEquals(3, result.size());
    assertEquals(
        EnumSet.of(ContributionType.CREATION),
        result.get(0).activities.get()
    );
    assertEquals(
        EnumSet.of(ContributionType.GEOMETRY_CHANGE),
        result.get(1).activities.get()
    );
    assertEquals(
        EnumSet.of(ContributionType.GEOMETRY_CHANGE),
        result.get(2).activities.get()
    );

    assertEquals(318, result.get(0).changeset);
    assertEquals(null, result.get(0).previousGeometry.get());
    assertTrue(result.get(1).geometry.get().isEmpty());
  }

  @Test
  public void testVisibleChangeOfOneWayOfOuterRing() {
    // relation, 2 way members making outer ring: way 120 changes visible tag later, 121 not
    // ways together making outer ring
    List<IterateAllEntry> result = (new CellIterator(
        new OSHDBTimestamps(
            "2000-01-01T00:00:00Z",
            "2020-01-01T00:00:00Z"
        ).get(),
        new OSHDBBoundingBox(-180,-90, 180, 90),
        areaDecider,
        oshEntity -> oshEntity.getId() == 512,
        osmEntity -> true,
        false
    )).iterateByContribution(
        oshdbDataGridCell
    ).collect(Collectors.toList());

    assertEquals(2, result.size());
    assertEquals(
        EnumSet.of(ContributionType.CREATION),
        result.get(0).activities.get()
    );
    assertEquals(
        EnumSet.of(ContributionType.GEOMETRY_CHANGE),
        result.get(1).activities.get()
    );
    Geometry geom = result.get(0).geometry.get();
    assertTrue(geom instanceof Polygon);
    Geometry geom2 = result.get(1).geometry.get();
    assertTrue(geom2 instanceof GeometryCollection);
    assertEquals(319, result.get(0).changeset);
    assertEquals(null, result.get(0).previousGeometry.get());
  }

  @Test
  public void testTagChangeOfWay() {
    // relation, way member: way 122 changes tags
    List<IterateAllEntry> result = (new CellIterator(
        new OSHDBTimestamps(
            "2000-01-01T00:00:00Z",
            "2020-01-01T00:00:00Z"
        ).get(),
        new OSHDBBoundingBox(-180,-90, 180, 90),
        areaDecider,
        oshEntity -> oshEntity.getId() == 513,
        osmEntity -> true,
        false
    )).iterateByContribution(
        oshdbDataGridCell
    ).collect(Collectors.toList());

    assertEquals(3, result.size());
    assertEquals(
        EnumSet.of(ContributionType.CREATION),
        result.get(0).activities.get()
    );
    Geometry geom = result.get(0).geometry.get();
    assertTrue(geom instanceof Polygon);
    Geometry geom2 = result.get(2).geometry.get();
    assertTrue(geom2 instanceof Polygon);
    assertEquals(320, result.get(0).changeset);
    assertEquals(null, result.get(0).previousGeometry.get());
  }

  @Test
  public void testOneOfTwoPolygonDisappears() {
    // relation, at the beginning two polygons, one disappears later
    List<IterateAllEntry> result = (new CellIterator(
        new OSHDBTimestamps(
            "2000-01-01T00:00:00Z",
            "2020-01-01T00:00:00Z"
        ).get(),
        new OSHDBBoundingBox(-180,-90, 180, 90),
        areaDecider,
        oshEntity -> oshEntity.getId() == 514,
        osmEntity -> true,
        false
    )).iterateByContribution(
        oshdbDataGridCell
    ).collect(Collectors.toList());

    assertEquals(2, result.size());
    assertEquals(
        EnumSet.of(ContributionType.CREATION),
        result.get(0).activities.get()
    );
    assertEquals(
        EnumSet.of(ContributionType.GEOMETRY_CHANGE),
        result.get(1).activities.get()
    );
    Geometry geom = result.get(0).geometry.get();
    assertTrue(geom instanceof MultiPolygon);
    Geometry geom2 = result.get(1).geometry.get();
    assertTrue(geom2 instanceof Polygon);
    assertEquals(321, result.get(0).changeset);
    assertEquals(null, result.get(0).previousGeometry.get());
    assertNotEquals(result.get(1).geometry.get(), result.get(1).previousGeometry.get());
  }

  @Test
  public void testWaySplitUpInTwo() {
    // relation, at the beginning one way, split up later
    List<IterateAllEntry> result = (new CellIterator(
        new OSHDBTimestamps(
            "2000-01-01T00:00:00Z",
            "2020-01-01T00:00:00Z"
        ).get(),
        new OSHDBBoundingBox(-180,-90, 180, 90),
        areaDecider,
        oshEntity -> oshEntity.getId() == 515,
        osmEntity -> true,
        false
    )).iterateByContribution(
        oshdbDataGridCell
    ).collect(Collectors.toList());
    assertEquals(3, result.size());
    assertEquals(
        EnumSet.of(ContributionType.CREATION),
        result.get(0).activities.get()
    );
    assertEquals(
        EnumSet.of(ContributionType.GEOMETRY_CHANGE),
        result.get(1).activities.get()
    );
    assertEquals(
        EnumSet.of(ContributionType.GEOMETRY_CHANGE),
        result.get(2).activities.get()
    );
    Geometry geom = result.get(0).geometry.get();
    assertTrue(geom instanceof Polygon);
    Geometry geom1 = result.get(1).geometry.get();
    assertTrue(geom1 instanceof GeometryCollection);
    Geometry geom2 = result.get(2).geometry.get();
    assertTrue(geom2 instanceof Polygon);
    assertEquals(323, result.get(0).changeset);
    assertEquals(null, result.get(0).previousGeometry.get());
    assertNotEquals(result.get(1).geometry.get(), result.get(1).previousGeometry.get());
  }


  @Test
  public void testPolygonIntersectingDataPartly() {

    GeometryFactory geometryFactory = new GeometryFactory();
    Coordinate[] coords=new Coordinate[5];
    coords[0]=new Coordinate(10.8,10.3);
    coords[1]=new Coordinate(10.8 ,22.7);
    coords[2]=new Coordinate(22.7,22.7);
    coords[3]=new Coordinate(22.7,10.3);
    coords[4]=new Coordinate(10.8,10.3);
    Polygon polygonFromCoordinates = geometryFactory.createPolygon(coords);

    List<IterateAllEntry> result = (new CellIterator(
        new OSHDBTimestamps(
            "2000-01-01T00:00:00Z",
            "2018-01-01T00:00:00Z"
        ).get(),
        polygonFromCoordinates,
        areaDecider,
        oshEntity -> oshEntity.getId() == 516,
        osmEntity -> true,
        false
    )).iterateByContribution(
        oshdbDataGridCell
    ).collect(Collectors.toList());
    assertEquals(3,result.size());
  }

  @Test
  public void testPolygonIntersectingDataOnlyAtBorderLine() {

    GeometryFactory geometryFactory = new GeometryFactory();
    Coordinate[] coords=new Coordinate[5];
    coords[0]=new Coordinate(10.7,10.4);
    coords[1]=new Coordinate(10.94,10.4);
    coords[2]=new Coordinate(10.94,10.9);
    coords[3]=new Coordinate(10.7,10.9);
    coords[4]=new Coordinate(10.7,10.4);
    Polygon polygonFromCoordinates = geometryFactory.createPolygon(coords);

    List<IterateAllEntry> result = (new CellIterator(
        new OSHDBTimestamps(
            "2000-01-01T00:00:00Z",
            "2018-01-01T00:00:00Z"
        ).get(),
        polygonFromCoordinates,
        areaDecider,
        oshEntity -> oshEntity.getId() == 516,
        osmEntity -> true,
        false
    )).iterateByContribution(
        oshdbDataGridCell
    ).collect(Collectors.toList());
    //assertEquals(3,result.size());
  }

  @Test
  public void testPolygonIntersectingDataCompletely() {

    GeometryFactory geometryFactory = new GeometryFactory();
    Coordinate[] coords=new Coordinate[5];
    coords[0]=new Coordinate(10.8,10.3);
    coords[1]=new Coordinate(10.8 ,52.7);
    coords[2]=new Coordinate(52.7,52.7);
    coords[3]=new Coordinate(52.7,10.3);
    coords[4]=new Coordinate(10.8,10.3);
    Polygon polygonFromCoordinates = geometryFactory.createPolygon(coords);

    List<IterateAllEntry> result = (new CellIterator(
        new OSHDBTimestamps(
            "2000-01-01T00:00:00Z",
            "2018-01-01T00:00:00Z"
        ).get(),
        polygonFromCoordinates,
        areaDecider,
        oshEntity -> oshEntity.getId() == 516,
        osmEntity -> true,
        false
    )).iterateByContribution(
        oshdbDataGridCell
    ).collect(Collectors.toList());
    assertEquals(3,result.size());
  }

  @Test
  public void testPolygonNotIntersectingData() {

    GeometryFactory geometryFactory = new GeometryFactory();
    Coordinate[] coords=new Coordinate[5];
    coords[0]=new Coordinate(48,49);
    coords[1]=new Coordinate(48 ,50);
    coords[2]=new Coordinate(49,50);
    coords[3]=new Coordinate(49,49);
    coords[4]=new Coordinate(48,49);
    Polygon polygonFromCoordinates = geometryFactory.createPolygon(coords);

    List<IterateAllEntry> result = (new CellIterator(
        new OSHDBTimestamps(
            "2000-01-01T00:00:00Z",
            "2018-01-01T00:00:00Z"
        ).get(),
        polygonFromCoordinates,
        areaDecider,
        oshEntity -> oshEntity.getId() == 516,
        osmEntity -> true,
        false
    )).iterateByContribution(
        oshdbDataGridCell
    ).collect(Collectors.toList());
    assertTrue(result.isEmpty());
  }

  @Test
  public void testNodeChangeOutsideBbox() {
    // relation: 2 ways, each has 5 points, making 1 polygon
    // nodes outside bbox have lon lat change in 2009 and 2011, the latest one affects geometry of
    // polygon inside bbox
    List<IterateAllEntry> result = (new CellIterator(
        new OSHDBTimestamps(
            "2000-01-01T00:00:00Z",
            "2019-08-01T00:00:00Z"
        ).get(),
        new OSHDBBoundingBox(10.8,10.3, 22.7, 22.7),
        areaDecider,
        oshEntity -> oshEntity.getId() == 516,
        osmEntity -> true,
        false
    )).iterateByContribution(
        oshdbDataGridCell
    ).collect(Collectors.toList());
    assertEquals(
        EnumSet.of(ContributionType.CREATION),
        result.get(0).activities.get()
    );
    assertTrue(result.get(1).activities.get().isEmpty());
    assertEquals(
        EnumSet.of(ContributionType.GEOMETRY_CHANGE),
        result.get(2).activities.get()
    );
    assertEquals(7, result.get(1).geometry.get().getNumPoints());
  }

  @Test
  public void testPolygonIntersectingDataCompletelyTimeIntervalAfterChanges() {

    GeometryFactory geometryFactory = new GeometryFactory();
    Coordinate[] coords=new Coordinate[5];
    coords[0]=new Coordinate(10.8,10.3);
    coords[1]=new Coordinate(10.8 ,52.7);
    coords[2]=new Coordinate(52.7,52.7);
    coords[3]=new Coordinate(52.7,10.3);
    coords[4]=new Coordinate(10.8,10.3);
    Polygon polygonFromCoordinates = geometryFactory.createPolygon(coords);

    List<IterateAllEntry> result = (new CellIterator(
        new OSHDBTimestamps(
            "2016-01-01T00:00:00Z",
            "2018-01-01T00:00:00Z"
        ).get(),
        polygonFromCoordinates,
        areaDecider,
        oshEntity -> oshEntity.getId() == 517,
        osmEntity -> true,
        false
    )).iterateByContribution(
        oshdbDataGridCell
    ).collect(Collectors.toList());
    assertTrue(result.isEmpty());
  }

  @Test
  public void testTimeIntervalAfterChanges() {

    List<IterateAllEntry> result = (new CellIterator(
        new OSHDBTimestamps(
            "2016-01-01T00:00:00Z",
            "2018-01-01T00:00:00Z"
        ).get(),
        new OSHDBBoundingBox(10.8,10.3, 52.7, 52.7),
        areaDecider,
        oshEntity -> oshEntity.getId() == 517,
        osmEntity -> true,
        false
    )).iterateByContribution(
        oshdbDataGridCell
    ).collect(Collectors.toList());
    assertTrue(result.isEmpty());
  }

  @Test
  public void testBboxOutsidePolygon() {
    // OSM Polygon coordinates between: minLon 10, maxLon 41, minLat 10, maxLat 45
    // OSHDBBoundingBox outside this coordinates

    List<IterateAllEntry> result = (new CellIterator(
        new OSHDBTimestamps(
            "2000-01-01T00:00:00Z",
            "2018-01-01T00:00:00Z"
        ).get(),
        new OSHDBBoundingBox(50,50, 52, 52),
        areaDecider,
        oshEntity -> oshEntity.getId() == 516,
        osmEntity -> true,
        false
    )).iterateByContribution(
        oshdbDataGridCell
    ).collect(Collectors.toList());
    assertTrue(result.isEmpty());

  }

  @Test
  public void testUnclippedGeom() {
    // relation: 2 ways, each has 5 points, making 1 polygon
    // geometry change of nodes of relation 2009 and 2011
    // OSHDBBoundingBox covers only left side of polygon
    // unclipped geom != clipped geom
    List<IterateAllEntry> result = (new CellIterator(
        new OSHDBTimestamps(
            "2000-01-01T00:00:00Z",
            "2019-08-01T00:00:00Z"
        ).get(),
        new OSHDBBoundingBox(10.8,10.3, 22.7, 22.7),
        areaDecider,
        oshEntity -> oshEntity.getId() == 516,
        osmEntity -> true,
        false
    )).iterateByContribution(
        oshdbDataGridCell
    ).collect(Collectors.toList());

    result.iterator().forEachRemaining(k -> System.out.println(k.timestamp.toString()));
    // .unclippedPreviousGeometry Returns the geometry of the entity before this modification. This is the full (unclipped)
    // geometry of the entity
    // .unclippedGeometry Returns the geometry of the entity after this modification. This is the full (unclipped)
    // geometry of the entity

    // full geom of same timestamp with unclippedPreviousGeometry and unclippedGeometry
    assertEquals(result.get(1).unclippedPreviousGeometry.get().getArea(),
        result.get(0).unclippedGeometry.get().getArea(),DELTA);
    // .geometry Returns the geometry of the entity after this modification clipped to the requested area
    // .unclippedGeometry Returns the geometry of the entity after this modification. This is the full (unclipped)
    // geometry of the entity

    // geom of requested area vs full geom after modification
    assertNotEquals(result.get(0).geometry.get().getArea(),
        result.get(0).unclippedGeometry.get().getArea());
    // full geom changed
    assertNotEquals(result.get(1).unclippedGeometry.get().getArea(),
        result.get(0).unclippedGeometry.get().getArea());
    assertNotEquals(result.get(1).unclippedGeometry.get().getArea(),
        result.get(2).unclippedGeometry.get().getArea());

  }

  @Test
  public void testSelfIntersectingPolygonClipped() {
    // Polygon with self crossing way
    // partly intersected by bbox polygon
    // happy if it works without crashing
    GeometryFactory geometryFactory = new GeometryFactory();
    Coordinate[] coords=new Coordinate[5];
    coords[0]=new Coordinate(7.31,1.0);
    coords[1]=new Coordinate(7.335,1.0);
    coords[2]=new Coordinate(7.335,2.0);
    coords[3]=new Coordinate(7.31,2.0);
    coords[4]=new Coordinate(7.31,1.0);
    Polygon polygonFromCoordinates = geometryFactory.createPolygon(coords);

    List<IterateAllEntry> result = (new CellIterator(
        new OSHDBTimestamps(
            "2000-01-01T00:00:00Z",
            "2018-01-01T00:00:00Z"
        ).get(),
        polygonFromCoordinates,
        areaDecider,
        oshEntity -> oshEntity.getId() == 520,
        osmEntity -> true,
        false
    )).iterateByContribution(
        oshdbDataGridCell
    ).collect(Collectors.toList());
    assertTrue(result.isEmpty());
  }

  @Test
  public void testMembersDisappear() {
    // relation with one way member(nodes of way have changes in 2009 and 2011), in version 2 member is deleted
    List<IterateAllEntry> result = (new CellIterator(
        new OSHDBTimestamps(
            "2000-01-01T00:00:00Z",
            "2020-01-01T00:00:00Z"
        ).get(),
        new OSHDBBoundingBox(-180,-90, 180, 90),
        areaDecider,
        oshEntity -> oshEntity.getId() == 521,
        osmEntity -> true,
        false
    )).iterateByContribution(
        oshdbDataGridCell
    ).collect(Collectors.toList());
    result.iterator().forEachRemaining(k -> System.out.println(k.timestamp.toString()));
    assertEquals(4, result.size());
    assertEquals(
        EnumSet.of(ContributionType.CREATION),
        result.get(0).activities.get()
    );
    result.iterator().forEachRemaining(k->System.out.println(k.activities.get()));
    assertTrue(result.get(3).geometry.get().isEmpty());
  }

  @Test
  public void testTimeIntervalAfterDeletionInVersion2() {
    // relation in second version visible = false, timeinterval includes version 3
    List<IterateAllEntry> result = (new CellIterator(
        new OSHDBTimestamps(
            "2016-01-01T00:00:00Z",
            "2020-01-01T00:00:00Z"
        ).get(),
        new OSHDBBoundingBox(-180,-90, 180, 90),
        areaDecider,
        oshEntity -> oshEntity.getId() == 522,
        osmEntity -> true,
        false
    )).iterateByContribution(
        oshdbDataGridCell
    ).collect(Collectors.toList());
    result.iterator().forEachRemaining(k -> System.out.println(k.timestamp.toString()));
    assertEquals(1, result.size());
    assertEquals(
        EnumSet.of(ContributionType.CREATION),
        result.get(0).activities.get()
    );

  }

  @Test
  public void testTimeIntervalAfterDeletionInCurrentVersion() {
    // relation in first and third version visible = false, timeinterval includes version 3
    List<IterateAllEntry> result = (new CellIterator(
        new OSHDBTimestamps(
            "2016-01-01T00:00:00Z",
            "2020-01-01T00:00:00Z"
        ).get(),
        new OSHDBBoundingBox(-180,-90, 180, 90),
        areaDecider,
        oshEntity -> oshEntity.getId() == 523,
        osmEntity -> true,
        false
    )).iterateByContribution(
        oshdbDataGridCell
    ).collect(Collectors.toList());
    result.iterator().forEachRemaining(k -> System.out.println(k.timestamp.toString()));
    assertEquals(1, result.size());
    assertEquals(
        EnumSet.of(ContributionType.DELETION),
        result.get(0).activities.get()
    );

  }

  @Test
  public void testExcludingVersion2() {
    // relation in second version visible = false, timeinterval includes version 3
    List<IterateAllEntry> result = (new CellIterator(
        new OSHDBTimestamps(
            "2012-01-01T00:00:00Z",
            "2014-01-01T00:00:00Z"
        ).get(),
        new OSHDBBoundingBox(-180,-90, 180, 90),
        areaDecider,
        oshEntity -> oshEntity.getId() == 500,
        osmEntity -> !(osmEntity.getVersion()==2),
        false
    )).iterateByContribution(
        oshdbDataGridCell
    ).collect(Collectors.toList());
    result.iterator().forEachRemaining(k -> System.out.println(k.timestamp.toString()));
    assertEquals(1, result.size());
    assertEquals(
        EnumSet.of(ContributionType.CREATION),
        result.get(0).activities.get()
    );

  }

  @Test
  public void testMembersDisappearClipped() {
    // relation with one way member(nodes of way have changes in 2009 and 2011), in version 2 member is deleted
    GeometryFactory geometryFactory = new GeometryFactory();
    Coordinate[] coords=new Coordinate[5];
    coords[0]=new Coordinate(10.8,10.3);
    coords[1]=new Coordinate(10.8 ,22.7);
    coords[2]=new Coordinate(22.7,22.7);
    coords[3]=new Coordinate(22.7,10.3);
    coords[4]=new Coordinate(10.8,10.3);
    Polygon polygonFromCoordinates = geometryFactory.createPolygon(coords);

    List<IterateAllEntry> result = (new CellIterator(
        new OSHDBTimestamps(
            "2000-01-01T00:00:00Z",
            "2020-01-01T00:00:00Z"
        ).get(),
        polygonFromCoordinates,
        areaDecider,
        oshEntity -> oshEntity.getId() == 521,
        osmEntity -> true,
        false
    )).iterateByContribution(
        oshdbDataGridCell
    ).collect(Collectors.toList());
    result.iterator().forEachRemaining(k -> System.out.println(k.timestamp.toString()));
    assertEquals(4, result.size());
    assertEquals(
        EnumSet.of(ContributionType.CREATION),
        result.get(0).activities.get()
    );
    assertEquals(
        EnumSet.of(ContributionType.DELETION),
        result.get(3).activities.get()
    );
    result.iterator().forEachRemaining(k->System.out.println(k.activities.get()));
    //result.iterator().forEachRemaining(k->System.out.println(k.geometry.get().toString()));

    assertTrue(result.get(3).geometry.get().isEmpty());
  }

  @Test
  public void testTimeIntervalAfterDeletionInVersion2Clipped() {
    // relation in second version visible = false, timeinterval includes version 3
    GeometryFactory geometryFactory = new GeometryFactory();
    Coordinate[] coords=new Coordinate[5];
    coords[0]=new Coordinate(10.8,10.3);
    coords[1]=new Coordinate(10.8 ,22.7);
    coords[2]=new Coordinate(22.7,22.7);
    coords[3]=new Coordinate(22.7,10.3);
    coords[4]=new Coordinate(10.8,10.3);
    Polygon polygonFromCoordinates = geometryFactory.createPolygon(coords);

    List<IterateAllEntry> result = (new CellIterator(
        new OSHDBTimestamps(
            "2016-01-01T00:00:00Z",
            "2020-01-01T00:00:00Z"
        ).get(),
        polygonFromCoordinates,
        areaDecider,
        oshEntity -> oshEntity.getId() == 522,
        osmEntity -> true,
        false
    )).iterateByContribution(
        oshdbDataGridCell
    ).collect(Collectors.toList());
    result.iterator().forEachRemaining(k -> System.out.println(k.timestamp.toString()));
    assertEquals(1, result.size());
    assertEquals(
        EnumSet.of(ContributionType.CREATION),
        result.get(0).activities.get()
    );

  }

  @Test
  public void testTimeIntervalAfterDeletionInCurrentVersionClipped() {
    // relation in first and third version visible = false, timeinterval includes version 3
    GeometryFactory geometryFactory = new GeometryFactory();
    Coordinate[] coords=new Coordinate[5];
    coords[0]=new Coordinate(10.8,10.3);
    coords[1]=new Coordinate(10.8 ,22.7);
    coords[2]=new Coordinate(22.7,22.7);
    coords[3]=new Coordinate(22.7,10.3);
    coords[4]=new Coordinate(10.8,10.3);
    Polygon polygonFromCoordinates = geometryFactory.createPolygon(coords);

    List<IterateAllEntry> result = (new CellIterator(
        new OSHDBTimestamps(
            "2016-01-01T00:00:00Z",
            "2020-01-01T00:00:00Z"
        ).get(),
        polygonFromCoordinates,
        areaDecider,
        oshEntity -> oshEntity.getId() == 523,
        osmEntity -> true,
        false
    )).iterateByContribution(
        oshdbDataGridCell
    ).collect(Collectors.toList());
    result.iterator().forEachRemaining(k -> System.out.println(k.timestamp.toString()));
    assertEquals(1, result.size());
    assertEquals(
        EnumSet.of(ContributionType.DELETION),
        result.get(0).activities.get()
    );
  }

  @Test
  public void testExcludingVersion2Clipped() {
    // relation in second version visible = false, timeinterval includes version 3
    GeometryFactory geometryFactory = new GeometryFactory();
    Coordinate[] coords=new Coordinate[5];
    coords[0]=new Coordinate(7.31,1.0);
    coords[1]=new Coordinate(7.335,1.0);
    coords[2]=new Coordinate(7.335,2.0);
    coords[3]=new Coordinate(7.31,2.0);
    coords[4]=new Coordinate(7.31,1.0);
    Polygon polygonFromCoordinates = geometryFactory.createPolygon(coords);

    List<IterateAllEntry> result = (new CellIterator(
        new OSHDBTimestamps(
            "2012-01-01T00:00:00Z",
            "2014-01-01T00:00:00Z"
        ).get(),
        polygonFromCoordinates,
        areaDecider,
        oshEntity -> oshEntity.getId() == 500,
        osmEntity -> !(osmEntity.getVersion()==2),
        false
    )).iterateByContribution(
        oshdbDataGridCell
    ).collect(Collectors.toList());
    result.iterator().forEachRemaining(k -> System.out.println(k.timestamp.toString()));
    assertEquals(1, result.size());
    assertEquals(
        EnumSet.of(ContributionType.CREATION),
        result.get(0).activities.get()
    );

  }

  @Test
  public void testClippingPolygonIsVeryBig() {
    // relation with two way members(nodes of ways have changes in 2009 and 2011)
    GeometryFactory geometryFactory = new GeometryFactory();
    Coordinate[] coords=new Coordinate[5];
    coords[0]=new Coordinate(-180,-90);
    coords[1]=new Coordinate(180 ,-90);
    coords[2]=new Coordinate(180,90);
    coords[3]=new Coordinate(-180,90);
    coords[4]=new Coordinate(-180,-90);
    Polygon polygonFromCoordinates = geometryFactory.createPolygon(coords);

    List<IterateAllEntry> result = (new CellIterator(
        new OSHDBTimestamps(
            "2008-01-01T00:00:00Z",
            "2020-01-01T00:00:00Z"
        ).get(),
        polygonFromCoordinates,
        areaDecider,
        oshEntity -> oshEntity.getId() == 516,
        osmEntity -> true,
        false
    )).iterateByContribution(
        oshdbDataGridCell
    ).collect(Collectors.toList());
    result.iterator().forEachRemaining(k -> System.out.println(k.timestamp.toString()));
    assertEquals(3, result.size());
  }
}
