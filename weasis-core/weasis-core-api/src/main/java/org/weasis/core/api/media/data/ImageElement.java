/*******************************************************************************
 * Copyright (c) 2010 Nicolas Roduit.
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the Eclipse Public License v1.0
 * which accompanies this distribution, and is available at
 * http://www.eclipse.org/legal/epl-v10.html
 * 
 * Contributors:
 *     Nicolas Roduit - initial API and implementation
 ******************************************************************************/
package org.weasis.core.api.media.data;

import java.awt.image.DataBuffer;
import java.awt.image.IndexColorModel;
import java.awt.image.RenderedImage;
import java.awt.image.renderable.ParameterBlock;
import java.io.IOException;
import java.lang.ref.Reference;
import java.lang.ref.SoftReference;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import javax.media.jai.JAI;
import javax.media.jai.PlanarImage;
import javax.media.jai.RenderedOp;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.weasis.core.api.image.LutShape;
import org.weasis.core.api.image.OperationsManager;
import org.weasis.core.api.image.measure.MeasurementsAdapter;
import org.weasis.core.api.image.util.ImageToolkit;
import org.weasis.core.api.image.util.Unit;

public class ImageElement extends MediaElement<PlanarImage> {

    /**
     * Logger for this class
     */
    private static final Logger logger = LoggerFactory.getLogger(ImageElement.class);
    // Imageio issue with native library in multi-thread environment (to avoid JVM crash let only one simultaneous
    // thread)
    // (https://jai-imageio-core.dev.java.net/issues/show_bug.cgi?id=126)
    public static final ExecutorService IMAGE_LOADER = Executors.newFixedThreadPool(1);

    private static final SoftHashMap<ImageElement, PlanarImage> mCache = new SoftHashMap<ImageElement, PlanarImage>() {

        public Reference<? extends PlanarImage> getReference(ImageElement key) {
            return hash.get(key);
        }

        @Override
        public void removeElement(Reference<? extends PlanarImage> soft) {
            ImageElement key = reverseLookup.remove(soft);
            if (key != null) {
                hash.remove(key);
                MediaReader<PlanarImage> reader = key.getMediaReader();
                key.setTag(TagW.ImageCache, false);
                if (reader != null) {
                    // Close the image stream
                    reader.close();
                }
            }
        }
    };
    protected boolean readable = true;

    protected double pixelSizeX = 1.0;
    protected double pixelSizeY = 1.0;
    protected Unit pixelSpacingUnit = Unit.PIXEL;
    protected String pixelSizeCalibrationDescription = null;
    protected String pixelValueUnit = null;

    protected float minPixelValue;
    protected float maxPixelValue;

    public ImageElement(MediaReader<PlanarImage> mediaIO, Object key) {
        super(mediaIO, key);
    }

    protected void findMinMaxValues(RenderedImage img) throws OutOfMemoryError {
        // This function can be called several times from the inner class Load.
        // Do not compute min and max it has already be done

        if (img != null && minPixelValue == 0.0f && maxPixelValue == 0.0f) {

            int datatype = img.getSampleModel().getDataType();
            if (datatype == DataBuffer.TYPE_BYTE) {
                this.minPixelValue = 0f;
                this.maxPixelValue = 255f;
            } else {

                ParameterBlock pb = new ParameterBlock();
                pb.addSource(img);
                // ImageToolkit.NOCACHE_HINT to ensure this image won't be stored in tile cache
                RenderedOp dst = JAI.create("extrema", pb, ImageToolkit.NOCACHE_HINT); //$NON-NLS-1$

                double[][] extrema = (double[][]) dst.getProperty("extrema"); //$NON-NLS-1$
                double min = Double.MAX_VALUE;
                double max = -Double.MAX_VALUE;
                int numBands = dst.getSampleModel().getNumBands();

                for (int i = 0; i < numBands; i++) {
                    min = Math.min(min, extrema[0][i]);
                    max = Math.max(max, extrema[1][i]);
                }
                this.minPixelValue = (int) min;
                this.maxPixelValue = (int) max;
                // this.minPixelValue = Math.round(min);
                // this.maxPixelValue = Math.round(max);

            }
        }
    }

    protected boolean isGrayImage(RenderedImage source) {
        // Binary images have indexColorModel
        if (source.getSampleModel().getNumBands() > 1 || source.getColorModel() instanceof IndexColorModel) {
            return false;
        }
        return true;
    }

    public float getPixelWindow(float window) {
        return window;
    }

    public float getPixelLevel(float level) {
        return level;
    }

    public LutShape getDefaultShape() {
        return LutShape.LINEAR;
    }

    public boolean getDefaultPixelPadding() {
        return true;
    }

    public float getDefaultWindow() {
        return maxPixelValue - minPixelValue;
    }

    public float getDefaultLevel() {
        return minPixelValue + (maxPixelValue - minPixelValue) / 2.f;
    }

    public float getMaxValue() {
        return maxPixelValue;
    }

    public float getMinValue() {
        return minPixelValue;
    }

    public int getRescaleWidth(int width) {
        return (int) Math.ceil(width * getRescaleX() - 0.5);
    }

    public int getRescaleHeight(int height) {
        return (int) Math.ceil(height * getRescaleY() - 0.5);
    }

    public double getRescaleX() {
        return pixelSizeX <= pixelSizeY ? 1.0 : pixelSizeX / pixelSizeY;
    }

    public double getRescaleY() {
        return pixelSizeY <= pixelSizeX ? 1.0 : pixelSizeY / pixelSizeX;
    }

    public double getPixelSize() {
        return pixelSizeX <= pixelSizeY ? pixelSizeX : pixelSizeY;
    }

    public void setPixelSize(double pixelSize) {
        if (pixelSizeX == pixelSizeY) {
            setPixelSize(pixelSize, pixelSize);
        } else if (pixelSizeX < pixelSizeY) {
            setPixelSize(pixelSize, (pixelSizeY / pixelSizeX) * pixelSize);
        } else {
            setPixelSize((pixelSizeX / pixelSizeY) * pixelSize, pixelSize);
        }
    }

    public void setPixelSize(double pixelSizeX, double pixelSizeY) {
        // Image is always displayed with a 1/1 aspect ratio, otherwise it becomes very
        // difficult (even impossible) to handle measurement tools.
        // When the ratio is not 1/1, the image is stretched. The smallest ratio keeps
        // the pixel size and the largest one is downscaled
        this.pixelSizeX = pixelSizeX <= 0.0 ? 1.0 : pixelSizeX;
        this.pixelSizeY = pixelSizeY <= 0.0 ? 1.0 : pixelSizeY;
    }

    public void setPixelValueUnit(String pixelValueUnit) {
        this.pixelValueUnit = pixelValueUnit;
    }

    public Unit getPixelSpacingUnit() {
        return pixelSpacingUnit;
    }

    public void setPixelSpacingUnit(Unit pixelSpacingUnit) {
        this.pixelSpacingUnit = pixelSpacingUnit;
    }

    public String getPixelValueUnit() {
        return pixelValueUnit;
    }

    public String getPixelSizeCalibrationDescription() {
        return pixelSizeCalibrationDescription;
    }

    public MeasurementsAdapter getMeasurementAdapter() {
        return new MeasurementsAdapter(getPixelSize(), 0, 0, false, 0, pixelSpacingUnit.getAbbreviation());
    }

    public boolean isImageInCache() {
        return mCache.get(this) != null;
    }

    public void removeImageFromCache() {
        SoftReference<PlanarImage> img = mCache.hash.get(this);
        if (img != null) {
            mCache.removeElement(img);
        }
    }

    public boolean hasSameSize(ImageElement image) {
        if (image != null) {
            PlanarImage img = getImage();
            PlanarImage img2 = image.getImage();
            if (img != null && img2 != null) {
                if (getRescaleWidth(img.getWidth()) == image.getRescaleWidth(img2.getWidth())
                    && getRescaleHeight(img.getHeight()) == image.getRescaleHeight(img2.getHeight())) {
                    return true;
                }
            }
        }
        return false;
    }

    /**
     * Loads the original image. Must load and return the original image.
     * 
     * @throws Exception
     * 
     * @throws IOException
     */

    protected PlanarImage loadImage() throws Exception {
        return mediaIO.getMediaFragment(this);
    }

    /**
     * @param imageSource
     *            is the RenderedImage upon which transformation is done
     * @param window
     *            is width from low to high input values around level. If null, getDefaultWindow() value is used
     * @param level
     *            is center of window values. If null, getDefaultLevel() value is used
     * @param lutShape
     *            defines the shape of applied lookup table transformation. If null getDefaultLutShape() is used
     * @param pixelPadding
     *            indicates if some padding values defined in ImageElement should be applied or not. If null, TRUE is
     *            considered
     * @return
     */

    public RenderedImage getRenderedImage(final RenderedImage imageSource, Float window, Float level,
        LutShape lutShape, Boolean pixelPadding) {
        if (imageSource == null) {
            return null;
        }

        window = (window == null) ? getDefaultWindow() : window;
        level = (level == null) ? getDefaultLevel() : level;
        lutShape = (lutShape == null) ? getDefaultShape() : lutShape;
        pixelPadding = (pixelPadding == null) ? true : pixelPadding;

        return ImageToolkit.getDefaultRenderedImage(this, imageSource, window, level, pixelPadding);
    }

    public RenderedImage getRenderedImage(final RenderedImage imageSource, Float window, Float level) {
        return getRenderedImage(imageSource, window, level, getDefaultShape(), getDefaultPixelPadding());
    }

    public RenderedImage getRenderedImage(final RenderedImage imageSource) {
        return getRenderedImage(imageSource, getDefaultWindow(), getDefaultLevel(), getDefaultShape(), true);
    }

    /**
     * Returns the full size, original image. Returns null if the image is not loaded.
     * 
     * @return
     */
    public synchronized PlanarImage getImage(OperationsManager manager) {
        PlanarImage cacheImage;
        try {
            cacheImage = startImageLoading();
        } catch (OutOfMemoryError e1) {
            // Appends when loading a big image without tiling, the memory left is not enough for the renderedop (like
            // Extrema)
            logger.warn("Out of MemoryError: {}", getMediaURI()); //$NON-NLS-1$
            mCache.expungeStaleEntries();
            System.gc();
            try {
                Thread.sleep(100);
            } catch (InterruptedException et) {
            }
            cacheImage = startImageLoading();
        }
        if (manager != null && cacheImage != null) {
            cacheImage = PlanarImage.wrapRenderedImage(manager.getFinalImage());
        }
        return cacheImage;
    }

    public PlanarImage getImage() {
        return getImage(null);
    }

    private PlanarImage startImageLoading() throws OutOfMemoryError {
        PlanarImage cacheImage;
        if ((cacheImage = mCache.get(this)) == null && readable && setAsLoading()) {
            logger.debug("Asking for reading image: {}", this); //$NON-NLS-1$
            Load ref = new Load();
            Future<PlanarImage> future = IMAGE_LOADER.submit(ref);
            PlanarImage img = null;
            try {
                img = future.get();

            } catch (InterruptedException e) {
                // Re-assert the thread's interrupted status
                Thread.currentThread().interrupt();
                // We don't need the result, so cancel the task too
                future.cancel(true);
            } catch (ExecutionException e) {
                if (e.getCause() instanceof OutOfMemoryError) {
                    setAsLoaded();
                    throw new OutOfMemoryError();
                } else {
                    readable = false;
                    logger.error("Cannot read pixel data!: {}", getMediaURI()); //$NON-NLS-1$
                    e.printStackTrace();
                }
            }
            if (img != null) {
                readable = true;
                mCache.put(this, img);
                cacheImage = img;
                this.setTag(TagW.ImageCache, true);
            }
            setAsLoaded();
        }
        return cacheImage;
    }

    // private void freeMemory() {
    // Entry<K, V> eldest = mCache..after;
    // if (removeEldestEntry(eldest)) {
    // removeEntryForKey(eldest.key);
    // }
    // }

    public boolean isReadable() {
        return readable;
    }

    @Override
    public void dispose() {
        // TODO find a clue to not dispose the display image
        // Or do nothing, let the soft reference mechanism do its job

        // Close image reader and image stream, but it should be already closed
        if (mediaIO != null) {
            mediaIO.close();
        }

        // // Unload image from memory
        // PlanarImage temp = mCache.remove(this);
        // if (temp != null) {
        // temp.dispose();
        //
        // }
        // if (image != null) {
        // PlanarImage temp = image.get();
        // if (temp != null) {
        // temp.dispose();
        // }
        // // image = null;
        // }
    }

    class Load implements Callable<PlanarImage> {

        @Override
        public PlanarImage call() throws Exception {
            PlanarImage img = loadImage();
            findMinMaxValues(img);
            return img;
        }
    }

}
