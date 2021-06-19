package com.onthegomap.flatmap.openmaptiles;

import static com.onthegomap.flatmap.openmaptiles.Expression.FALSE;
import static com.onthegomap.flatmap.openmaptiles.Expression.TRUE;
import static com.onthegomap.flatmap.openmaptiles.Expression.matchType;

import com.graphhopper.reader.ReaderElement;
import com.graphhopper.reader.ReaderElementUtils;
import com.graphhopper.reader.ReaderRelation;
import com.onthegomap.flatmap.Arguments;
import com.onthegomap.flatmap.FeatureCollector;
import com.onthegomap.flatmap.Profile;
import com.onthegomap.flatmap.SourceFeature;
import com.onthegomap.flatmap.Translations;
import com.onthegomap.flatmap.VectorTileEncoder;
import com.onthegomap.flatmap.geo.GeometryException;
import com.onthegomap.flatmap.monitoring.Stats;
import com.onthegomap.flatmap.openmaptiles.generated.OpenMapTilesSchema;
import com.onthegomap.flatmap.openmaptiles.generated.Tables;
import com.onthegomap.flatmap.read.OpenStreetMapReader;
import com.onthegomap.flatmap.read.ReaderFeature;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class OpenMapTilesProfile implements Profile {

  private static final boolean MERGE_Z13_BUILDINGS = false;

  public static final String LAKE_CENTERLINE_SOURCE = "lake_centerlines";
  public static final String WATER_POLYGON_SOURCE = "water_polygons";
  public static final String NATURAL_EARTH_SOURCE = "natural_earth";
  public static final String OSM_SOURCE = "osm";
  private static final Logger LOGGER = LoggerFactory.getLogger(OpenMapTilesProfile.class);
  private final MultiExpression.MultiExpressionIndex<Tables.Constructor> osmPointMappings;
  private final MultiExpression.MultiExpressionIndex<Tables.Constructor> osmLineMappings;
  private final MultiExpression.MultiExpressionIndex<Tables.Constructor> osmPolygonMappings;
  private final MultiExpression.MultiExpressionIndex<Tables.Constructor> osmRelationMemberMappings;
  private final List<Layer> layers;
  private final Map<Class<? extends Tables.Row>, List<Tables.RowHandler<Tables.Row>>> osmDispatchMap;
  private final Map<String, FeaturePostProcessor> postProcessors;
  private final List<NaturalEarthProcessor> naturalEarthProcessors;
  private final List<OsmWaterPolygonProcessor> osmWaterProcessors;
  private final List<LakeCenterlineProcessor> lakeCenterlineProcessors;
  private final List<OsmAllProcessor> osmAllProcessors;

  private MultiExpression.MultiExpressionIndex<Tables.Constructor> indexForType(String type) {
    return Tables.MAPPINGS
      .filterKeys(constructor -> {
        // exclude any mapping that generates a class we don't have a handler for
        var clz = constructor.create(new ReaderFeature(null, Map.of(), 0), "").getClass();
        return !osmDispatchMap.getOrDefault(clz, List.of()).isEmpty();
      })
      .replace(matchType(type), TRUE)
      .replace(e -> e instanceof Expression.MatchType, FALSE)
      .simplify()
      .index();
  }

  public OpenMapTilesProfile(Translations translations, Arguments arguments, Stats stats) {
    this.layers = OpenMapTilesSchema.createInstances(translations, arguments, stats);
    osmDispatchMap = new HashMap<>();
    Tables.generateDispatchMap(layers).forEach((clazz, handlers) -> {
      osmDispatchMap.put(clazz, handlers.stream().map(handler -> {
        @SuppressWarnings("unchecked") Tables.RowHandler<Tables.Row> rawHandler = (Tables.RowHandler<Tables.Row>) handler;
        return rawHandler;
      }).toList());
    });
    this.osmPointMappings = indexForType("point");
    this.osmLineMappings = indexForType("linestring");
    this.osmPolygonMappings = indexForType("polygon");
    this.osmRelationMemberMappings = indexForType("relation_member");
    postProcessors = new HashMap<>();
    osmAllProcessors = new ArrayList<>();
    lakeCenterlineProcessors = new ArrayList<>();
    naturalEarthProcessors = new ArrayList<>();
    osmWaterProcessors = new ArrayList<>();
    for (Layer layer : layers) {
      if (layer instanceof FeaturePostProcessor postProcessor) {
        postProcessors.put(layer.name(), postProcessor);
      }
      if (layer instanceof OsmAllProcessor processor) {
        osmAllProcessors.add(processor);
      }
      if (layer instanceof OsmWaterPolygonProcessor processor) {
        osmWaterProcessors.add(processor);
      }
      if (layer instanceof LakeCenterlineProcessor processor) {
        lakeCenterlineProcessors.add(processor);
      }
      if (layer instanceof NaturalEarthProcessor processor) {
        naturalEarthProcessors.add(processor);
      }
    }
  }

  @Override
  public void release() {
    layers.forEach(Layer::release);
  }

  @Override
  public List<VectorTileEncoder.Feature> postProcessLayerFeatures(String layer, int zoom,
    List<VectorTileEncoder.Feature> items) throws GeometryException {
    FeaturePostProcessor postProcesor = postProcessors.get(layer);
    List<VectorTileEncoder.Feature> result = null;
    if (postProcesor != null) {
      result = postProcesor.postProcess(zoom, items);
    }
//    if (MERGE_Z13_BUILDINGS && "building".equals(layer) && zoom == 13) {
//      return FeatureMerge.mergePolygons(items, 4, 0.5, 0.5);
//    }
    return result == null ? items : result;
  }

  @Override
  public List<OpenStreetMapReader.RelationInfo> preprocessOsmRelation(ReaderRelation relation) {
    return null;
  }

  @Override
  public void processFeature(SourceFeature sourceFeature, FeatureCollector features) {
    switch (sourceFeature.getSource()) {
      case OSM_SOURCE -> {
        for (var match : getTableMatches(sourceFeature)) {
          var row = match.match().create(sourceFeature, match.keys().get(0));
          var handlers = osmDispatchMap.get(row.getClass());
          if (handlers != null) {
            for (Tables.RowHandler<Tables.Row> handler : handlers) {
              handler.process(row, features);
            }
          }
        }
        for (int i = 0; i < osmAllProcessors.size(); i++) {
          osmAllProcessors.get(i).processAllOsm(sourceFeature, features);
        }
      }
      case LAKE_CENTERLINE_SOURCE -> {
        for (LakeCenterlineProcessor lakeCenterlineProcessor : lakeCenterlineProcessors) {
          lakeCenterlineProcessor.processLakeCenterline(sourceFeature, features);
        }
      }
      case NATURAL_EARTH_SOURCE -> {
        for (NaturalEarthProcessor naturalEarthProcessor : naturalEarthProcessors) {
          naturalEarthProcessor.processNaturalEarth(sourceFeature.getSourceLayer(), sourceFeature, features);
        }
      }
      case WATER_POLYGON_SOURCE -> {
        for (OsmWaterPolygonProcessor osmWaterProcessor : osmWaterProcessors) {
          osmWaterProcessor.processOsmWater(sourceFeature, features);
        }
      }
    }

//
//    if (sourceFeature.isPoint()) {
//      if (sourceFeature.hasTag("natural", "peak", "volcano")) {
//        features.point("mountain_peak")
//          .setAttr("name", sourceFeature.getTag("name"))
//          .setLabelGridSizeAndLimit(13, 100, 5);
//      }
//    }
//
//    if (WATER_POLYGON_SOURCE.equals(sourceFeature.getSource())) {
//      features.polygon("water").setZoomRange(6, 14).setAttr("class", "ocean");
//    } else if (NATURAL_EARTH_SOURCE.equals(sourceFeature.getSource())) {
//      String sourceLayer = sourceFeature.getSourceLayer();
//      boolean lake = sourceLayer.endsWith("_lakes");
//      switch (sourceLayer) {
//        case "ne_10m_lakes", "ne_10m_ocean" -> features.polygon("water")
//          .setZoomRange(4, 5)
//          .setAttr("class", lake ? "lake" : "ocean");
//        case "ne_50m_lakes", "ne_50m_ocean" -> features.polygon("water")
//          .setZoomRange(2, 3)
//          .setAttr("class", lake ? "lake" : "ocean");
//        case "ne_110m_lakes", "ne_110m_ocean" -> features.polygon("water")
//          .setZoomRange(0, 1)
//          .setAttr("class", lake ? "lake" : "ocean");
//      }
//    }
//
//    if (OSM_SOURCE.equals(sourceFeature.getSource())) {
//      if (sourceFeature.canBePolygon()) {
//        if (sourceFeature.hasTag("building")) {
//          features.polygon("building")
//            .setZoomRange(13, 14)
//            .setMinPixelSize(MERGE_Z13_BUILDINGS ? 0 : 4);
//        }
//      }
//    }
  }

  List<MultiExpression.MultiExpressionIndex.MatchWithTriggers<Tables.Constructor>> getTableMatches(
    SourceFeature sourceFeature) {
    List<MultiExpression.MultiExpressionIndex.MatchWithTriggers<Tables.Constructor>> result = null;
    if (sourceFeature.isPoint()) {
      result = osmPointMappings.getMatchesWithTriggers(sourceFeature.properties());
    } else {
      if (sourceFeature.canBeLine()) {
        result = osmLineMappings.getMatchesWithTriggers(sourceFeature.properties());
        if (sourceFeature.canBePolygon()) {
          result.addAll(osmPolygonMappings.getMatchesWithTriggers(sourceFeature.properties()));
        }
      } else if (sourceFeature.canBePolygon()) {
        result = osmPolygonMappings.getMatchesWithTriggers(sourceFeature.properties());
      }
    }
    return result == null ? List.of() : result;
  }

  public interface NaturalEarthProcessor {

    void processNaturalEarth(String table, SourceFeature feature, FeatureCollector features);
  }

  public interface LakeCenterlineProcessor {

    void processLakeCenterline(SourceFeature feature, FeatureCollector features);
  }

  public interface OsmWaterPolygonProcessor {

    void processOsmWater(SourceFeature feature, FeatureCollector features);
  }

  public interface OsmAllProcessor {

    void processAllOsm(SourceFeature feature, FeatureCollector features);
  }

  public interface FeaturePostProcessor {

    List<VectorTileEncoder.Feature> postProcess(int zoom, List<VectorTileEncoder.Feature> items)
      throws GeometryException;
  }

  @Override
  public boolean caresAboutWikidataTranslation(ReaderElement elem) {
    var tags = ReaderElementUtils.getProperties(elem);
    return switch (elem.getType()) {
      case ReaderElement.WAY -> osmPolygonMappings.matches(tags) || osmLineMappings.matches(tags);
      case ReaderElement.NODE -> osmPointMappings.matches(tags);
      case ReaderElement.RELATION -> osmPolygonMappings.matches(tags);
      default -> false;
    };
  }

  @Override
  public String name() {
    return OpenMapTilesSchema.NAME;
  }

  @Override
  public String description() {
    return OpenMapTilesSchema.DESCRIPTION;
  }

  @Override
  public String attribution() {
    return OpenMapTilesSchema.ATTRIBUTION;
  }

  @Override
  public String version() {
    return OpenMapTilesSchema.VERSION;
  }
}