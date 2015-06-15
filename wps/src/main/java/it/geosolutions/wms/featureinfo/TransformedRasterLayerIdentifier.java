package it.geosolutions.wms.featureinfo;

import java.awt.Rectangle;
import java.awt.geom.Rectangle2D;
import java.awt.image.RenderedImage;
import java.io.IOException;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.logging.Level;
import java.util.logging.Logger;

import javax.media.jai.PlanarImage;
import org.opengis.feature.type.Name;

import org.geoserver.catalog.CoverageInfo;
import org.geoserver.platform.ExtensionPriority;
import org.geoserver.wms.FeatureInfoRequestParameters;
import org.geoserver.wms.GetMapRequest;
import org.geoserver.wms.MapLayerInfo;
import org.geoserver.wms.RenderingVariables;
import org.geoserver.wms.WMS;
import org.geoserver.wms.WMSMapContent;
import org.geoserver.wms.featureinfo.RasterLayerIdentifier;
import org.geoserver.wms.map.RasterSymbolizerVisitor;
import org.geotools.coverage.GridSampleDimension;
import org.geotools.coverage.grid.GridCoverage2D;
import org.geotools.coverage.grid.GridEnvelope2D;
import org.geotools.coverage.grid.GridGeometry2D;
import org.geotools.coverage.grid.io.AbstractGridFormat;
import org.geotools.coverage.grid.io.GridCoverage2DReader;
import org.geotools.data.DataUtilities;
import org.geotools.data.Query;
import org.geotools.data.collection.CollectionFeatureSource;
import org.geotools.data.simple.SimpleFeatureCollection;
import org.geotools.factory.GeoTools;
import org.geotools.factory.Hints;
import org.geotools.feature.FeatureCollection;
import org.geotools.feature.SchemaException;
import org.geotools.feature.simple.SimpleFeatureBuilder;
import org.geotools.feature.simple.SimpleFeatureTypeBuilder;
import org.geotools.filter.function.EnvFunction;
import org.geotools.geometry.DirectPosition2D;
import org.geotools.geometry.TransformedDirectPosition;
import org.geotools.geometry.jts.ReferencedEnvelope;
import org.geotools.parameter.Parameter;
import org.geotools.renderer.lite.RenderingTransformationHelper;
import org.geotools.resources.coverage.FeatureUtilities;
import org.geotools.resources.geometry.XRectangle2D;
import org.geotools.resources.image.ImageUtilities;
import org.geotools.styling.Style;
import org.geotools.util.NullProgressListener;
import org.geotools.util.logging.Logging;
import org.opengis.coverage.CannotEvaluateException;
import org.opengis.coverage.PointOutsideCoverageException;
import org.opengis.coverage.grid.GridEnvelope;
import org.opengis.feature.simple.SimpleFeatureType;
import org.opengis.filter.Filter;
import org.opengis.filter.expression.Expression;
import org.opengis.geometry.DirectPosition;
import org.opengis.geometry.MismatchedDimensionException;
import org.opengis.parameter.GeneralParameterValue;
import org.opengis.referencing.crs.CoordinateReferenceSystem;
import org.opengis.referencing.datum.PixelInCell;
import org.opengis.referencing.operation.MathTransform;
import org.opengis.referencing.operation.TransformException;

import com.vividsolutions.jts.geom.Coordinate;

public class TransformedRasterLayerIdentifier extends RasterLayerIdentifier implements ExtensionPriority {
    
    static final Logger LOGGER = Logging.getLogger(TransformedRasterLayerIdentifier.class);
    
    WMS wms;
    public TransformedRasterLayerIdentifier(WMS wms) {
        super(wms);
        this.wms = wms;
    }

    @Override
    public int getPriority() {
        return (ExtensionPriority.LOWEST + ExtensionPriority.HIGHEST) / 2;
    }
    
    private Expression getRenderingTransformation(FeatureInfoRequestParameters params) {
        
        Style style = params.getStyle();

        RasterSymbolizerVisitor visitor = new RasterSymbolizerVisitor(0.0, null);
        style.accept(visitor);

        Expression transformation = visitor.getRasterRenderingTransformation();
        return transformation;
    }
    
    @Override
    public List<FeatureCollection> identify(FeatureInfoRequestParameters params, int maxFeatures)
            throws Exception {
        Expression transformation = getRenderingTransformation(params);
        if(transformation != null) {
            final Coordinate middle = WMS.pixelToWorld(params.getX(), params.getY(), params.getRequestedBounds(), params.getWidth(), params.getHeight());
            CoordinateReferenceSystem requestedCRS = params.getRequestedCRS();
            DirectPosition position = new DirectPosition2D(requestedCRS, middle.x, middle.y);
            GetMapRequest getMap = params.getGetMapRequest();
            GridCoverage2D coverage = getOriginalCoverage(params, maxFeatures, position, getMap);
            final GridCoverage2D helperCoverage = coverage;
            RenderingTransformationHelper helper = new RenderingTransformationHelper() {
                
                @Override
                protected GridCoverage2D readCoverage(GridCoverage2DReader arg0, Object arg1,
                        GridGeometry2D arg2) throws IOException {
                    return helperCoverage;
                }
            };
            setupEnvironmentVariables(params, getMap);
            
            Object transformed = helper.applyRenderingTransformation(transformation, new CollectionFeatureSource(FeatureUtilities.wrapGridCoverage(helperCoverage)), 
                    Query.ALL, Query.ALL, coverage.getGridGeometry(), coverage.getCoordinateReferenceSystem(), null);
            if(transformed instanceof GridCoverage2D) {
                coverage = (GridCoverage2D)transformed;
                position = reprojectPosition(middle, requestedCRS, coverage.getCoordinateReferenceSystem());
            }
            
            FeatureCollection pixel = null;
            try {
                final double[] pixelValues = coverage.evaluate(position, (double[]) null);
                pixel = wrapPixelInFeatureCollection(coverage, pixelValues, params.getLayer()
                        .getCoverage().getQualifiedName());
            } catch (PointOutsideCoverageException e) {
                // it's fine, users might legitimately query point outside, we just don't
                // return anything
            } finally {
                RenderedImage ri = coverage.getRenderedImage();
                coverage.dispose(true);
                if(ri instanceof PlanarImage) {
                    ImageUtilities.disposePlanarImageChain((PlanarImage) ri);
                }
            }
            return Collections.singletonList(pixel);
        } else {
            return super.identify(params, maxFeatures);
        }
    }
    
    private DirectPosition reprojectPosition(final Coordinate middle,
            CoordinateReferenceSystem requestedCRS, CoordinateReferenceSystem targetCRS) {
        DirectPosition position = new DirectPosition2D(requestedCRS, middle.x, middle.y);
        if (requestedCRS != null) {
            
            final TransformedDirectPosition arbitraryToInternal = new TransformedDirectPosition(
                    requestedCRS, targetCRS, new Hints(Hints.LENIENT_DATUM_SHIFT,
                            Boolean.TRUE));
            try {
                arbitraryToInternal.transform(position);
            } catch (TransformException exception) {
                throw new CannotEvaluateException("Unable to answer the geatfeatureinfo",
                        exception);
            }
            position = arbitraryToInternal;
        }
        return position;
    }

    private GridCoverage2D getOriginalCoverage(FeatureInfoRequestParameters params,
            int maxFeatures, DirectPosition position, GetMapRequest getMap) throws IOException,
            MismatchedDimensionException, TransformException {
        final MapLayerInfo layer = params.getLayer();
        final Filter filter = params.getFilter(); 
        final CoverageInfo cinfo = layer.getCoverage();
        final GridCoverage2DReader reader = (GridCoverage2DReader) cinfo
                .getGridCoverageReader(new NullProgressListener(),
                        GeoTools.getDefaultHints());
        
        // now get the position in raster space using the world to grid related to
        // corner
        final MathTransform worldToGrid = reader.getOriginalGridToWorld(PixelInCell.CELL_CORNER)
                .inverse();
        final DirectPosition rasterMid = worldToGrid.transform(position, null);
        // create a 20X20 rectangle aruond the mid point and then intersect with the
        // original range
        final Rectangle2D.Double rasterArea = new Rectangle2D.Double();
        rasterArea.setFrameFromCenter(rasterMid.getOrdinate(0), rasterMid.getOrdinate(1),
                rasterMid.getOrdinate(0) + 10, rasterMid.getOrdinate(1) + 10);
        final Rectangle integerRasterArea = rasterArea.getBounds();
        final GridEnvelope gridEnvelope = reader.getOriginalGridRange();
        final Rectangle originalArea = (gridEnvelope instanceof GridEnvelope2D) ? (GridEnvelope2D) gridEnvelope
                : new Rectangle();
        XRectangle2D.intersect(integerRasterArea, originalArea, integerRasterArea);
        // paranoiac check, did we fall outside the coverage raster area? This should
        // never really happne if the request is well formed.
        if (integerRasterArea.isEmpty()) {
            return null;
        }
        
        // read from the request
        
        GeneralParameterValue[] parameters = wms.getWMSReadParameters(getMap, 
                layer, filter, params.getTimes(), params.getElevations(), reader, true);
        
        // now set the grid geometry for this request
        for (int k = 0; k < parameters.length; k++) {
            if (!(parameters[k] instanceof Parameter<?>))
                continue;

            final Parameter<?> parameter = (Parameter<?>) parameters[k];
            if (parameter.getDescriptor().getName()
                    .equals(AbstractGridFormat.READ_GRIDGEOMETRY2D.getName())) {
                //
                // create a suitable geometry for this request reusing the getmap (we
                // could probably optimize)
                //
                parameter.setValue(new GridGeometry2D(new GridEnvelope2D(integerRasterArea), reader
                        .getOriginalGridToWorld(PixelInCell.CELL_CENTER), reader.getCoordinateReferenceSystem()));
            }

        }
        
        final GridCoverage2D coverage = (GridCoverage2D) reader.read(parameters);
        if (coverage == null) {
            if (LOGGER.isLoggable(Level.FINE))
                LOGGER.fine("Unable to load raster data for this request.");
            return null;
        }
        return coverage;
    }
    
    private void setupEnvironmentVariables(FeatureInfoRequestParameters params, GetMapRequest getMap) {
        WMSMapContent mc = new WMSMapContent(params.getGetMapRequest());
            
        // prepare the fake web map content
        mc.setTransparent(true);
        mc.setBuffer(params.getBuffer());
        mc.getViewport().setBounds(new ReferencedEnvelope(getMap.getBbox(), getMap.getCrs()));
        mc.setMapWidth(getMap.getWidth());
        mc.setMapHeight(getMap.getHeight());
        
        // setup the env variables just like in the original GetMap
        RenderingVariables.setupEnvironmentVariables(mc);
        
        for(Object key : params.getGetMapRequest().getEnv().keySet()) {
            EnvFunction.setLocalValue((String)key, params.getGetMapRequest().getEnv().get(key));
        }
    }
    
    private SimpleFeatureCollection wrapPixelInFeatureCollection(GridCoverage2D coverage,
            double[] pixelValues, Name coverageName) throws SchemaException {

        GridSampleDimension[] sampleDimensions = coverage.getSampleDimensions();

        SimpleFeatureTypeBuilder builder = new SimpleFeatureTypeBuilder();
        builder.setName(coverageName);
        final Set<String> bandNames = new HashSet<String>();
        for (int i = 0; i < sampleDimensions.length; i++) {
            String name = sampleDimensions[i].getDescription().toString();
            // GEOS-2518
            if (bandNames.contains(name))
                // it might happen again that the name already exists but it pretty difficult I'd
                // say
                name = new StringBuilder(name).append("_Band").append(i).toString();
            bandNames.add(name);
            builder.add(name, Double.class);
        }
        SimpleFeatureType gridType = builder.buildFeatureType();

        Double[] values = new Double[pixelValues.length];
        for (int i = 0; i < values.length; i++) {
            values[i] = new Double(pixelValues[i]);
        }
        return DataUtilities.collection(SimpleFeatureBuilder.build(gridType, values, ""));
    }

}
