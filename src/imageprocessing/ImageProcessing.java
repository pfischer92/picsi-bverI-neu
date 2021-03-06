package imageprocessing;

import main.Picsi;
import org.eclipse.swt.graphics.RGB;
import utils.Parallel;

import org.eclipse.swt.SWT;
import org.eclipse.swt.graphics.ImageData;

/**
 * Image processing class: contains widely used image processing functions
 * SWT help: https://www.eclipse.org/swt/javadoc.php
 * 
 * @author Christoph Stamm
 *
 */
public class ImageProcessing {
	/**
	 * Compute PSNR of two images of the same image type
	 * @param inData1
	 * @param inData2
	 * @param imageType
	 * @return double-array of length 1 or 3 containing the separate PSNR of each channel
	 */
	public static double[] psnr(ImageData inData1, ImageData inData2, int imageType) {
		final int size = inData1.width*inData1.height;
		final int len = (imageType == Picsi.IMAGE_TYPE_INDEXED || imageType == Picsi.IMAGE_TYPE_RGB) ? 3 : 1;
		double[] PSNR = new double[len];

		Parallel.For(0, inData1.height, PSNR,
				// creator
				() -> new double[len],
				// loop body
				(v, psnr) -> {
					for (int u=0; u < inData1.width; u++) {
						final int pixel1 = inData1.getPixel(u, v);
						final int pixel2 = inData2.getPixel(u, v);
						if (len == 1) {
							psnr[0] = (pixel2 - pixel1)*(pixel2 - pixel1);
						} else {
							RGB rgb1 = inData1.palette.getRGB(pixel1);
							RGB rgb2 = inData2.palette.getRGB(pixel2);
							psnr[0] += (rgb2.red - rgb1.red)*(rgb2.red - rgb1.red);
							psnr[1] += (rgb2.green - rgb1.green)*(rgb2.green - rgb1.green);
							psnr[2] += (rgb2.blue - rgb1.blue)*(rgb2.blue - rgb1.blue);
						}
					}
				},
				// reducer
				psnr -> {
					for(int i=0; i < psnr.length; i++) PSNR[i] += psnr[i];
				}
		);
		for(int i=0; i < PSNR.length; i++) PSNR[i] = 20*Math.log10(255/Math.sqrt(PSNR[i]/size));
		return PSNR;
	}

	/**
	 * Clamp to range [-128,127]
	 * @param d
	 * @return
	 */
	public static int signedClamp8(double d) {
		if (d < -128) {
			return -128;
		} else if (d > 127) {
			return 127;
		} else {
			return (int)Math.round(d);
		}
	}
	
	/**
	 * Clamp to range [0,255]
	 * @param v
	 * @return
	 */
	public static int clamp8(int v) {
		// needs only one test in the usual case
		if ((v & 0xFFFFFF00) != 0) 
			return (v < 0) ? 0 : 255; 
		else 
			return v;
		//return (v >= 0) ? (v < 256 ? v : 255) : 0;
	}
	
	/**
	 * Clamp to range [0,255]
	 * @param d
	 * @return
	 */
	public static int clamp8(double d) {
		if (d < 0) {
			return 0;
		} else if (d > 255) {
			return 255;
		} else {
			return (int)Math.round(d);
		}
	}

	/**
	 * Compute image histogram with nClasses
	 * @param inData
	 * @param nClasses <= 256
	 * @return
	 */
	public static int[] histogram(ImageData inData, int nClasses) {
		final int maxClasses = 1 << inData.depth;
		assert 0 < nClasses && nClasses <= maxClasses : "wrong number of classes: " + nClasses;
		
		int[] histo = new int[nClasses];
		
		Parallel.For(0, inData.height, histo,
			// creator
			() -> new int[nClasses],
			// loop body
			(v, h) -> {
				for (int u=0; u < inData.width; u++) {
					h[inData.getPixel(u, v)*nClasses/maxClasses]++;
				}
			},
			// reducer
			h -> {
				for(int i=0; i < histo.length; i++) histo[i] += h[i];
			}
		);
		return histo;
	}

	/**
	 * Compute RGB image histogram for a selected channel
	 * @param inData
	 * @param channel [0..2]
	 * @return
	 */
	public static int[] histogramRGB(ImageData inData, int channel) {
		final int nClasses = 256;
		assert inData.palette.isDirect : "wrong image type";
	
		int[] histo = new int[nClasses];
		final int mask, shift;
		switch(channel) {
		case 0: mask = inData.palette.redMask; shift = inData.palette.redShift; break;
		case 1: mask = inData.palette.greenMask; shift = inData.palette.greenShift; break;
		default: mask = inData.palette.blueMask; shift = inData.palette.blueShift; break;
		}
		
		Parallel.For(0, inData.height, histo,
			// creator
			() -> new int[nClasses],
			// loop body
			(v, h) -> {
				for (int u=0; u < inData.width; u++) {
					final int pixel = inData.getPixel(u, v);
					// mask can be negative -> use >>> instead of >>
					h[(shift > 0) ? (mask & pixel) << shift : (mask & pixel) >>> -shift]++;					
				}
			},
			// reducer
			h -> {
				for(int i=0; i < histo.length; i++) histo[i] += h[i];
			}
		);
		return histo;
	}
	
	/**
	 * Crops input image of given input rectangle
	 */
	public static ImageData crop(ImageData inData, int x, int y, int w, int h) {
		ImageData outData = new ImageData(w, h, inData.depth, inData.palette);
		
		for (int v=0; v < h; v++) {
			for (int u=0; u < w; u++) {
				outData.setPixel(u, v, inData.getPixel(u + x, v + y));
			}
			if (inData.getTransparencyType() == SWT.TRANSPARENCY_ALPHA) {
				for (int u=0; u < w; u++) {
					outData.setAlpha(u, v, inData.getAlpha(u + x, v + y));
				}
			}
		}
		return outData;
	}
	
	/**
	 * Inserts image insData into image data at position (x,y)
	 */
	public static boolean insert(ImageData data, ImageData insData, int x, int y) {
		if (data.depth != insData.depth) return false;
		int x2 = Math.min(data.width, x + insData.width);
		int y2 = Math.min(data.height, y + insData.height);
		
		for (int v=y; v < y2; v++) {
			for (int u=x; u < x2; u++) {
				data.setPixel(u, v, insData.getPixel(u - x, v - y));
			}
		}
		return true;
	}
	/**
	 * Apply lookup table lut on image data inData
	 * @param inData
	 * @param lut
	 */
	public static void applyLUT(ImageData inData, byte[] lut) {
		byte[] data = inData.data;

		//for (int i=0; i < data.length; i++) data[i] = lut[0xFF & data[i]];
		Parallel.For(0, data.length, i -> data[i] = lut[0xFF & data[i]]);
	}

	/**
	 * Convolve inData
	 * @param inData
	 * @param imageType
	 * @param filter 2D filter matrix
	 * @param den denominator for filter normalization
	 * @param offset intensity offset
	 * @return
	 */
	public static ImageData convolve(ImageData inData, int imageType, int[][] filter, int den, int offset) {
		assert den > 0 : "wrong denominator";

		ImageData outData = (ImageData)inData.clone();
		final int fSizeJD2 = filter.length/2;

		if (imageType == Picsi.IMAGE_TYPE_GRAY) {
			Parallel.For(0, outData.height, v -> {
				for (int u=0; u < outData.width; u++) {
					int sum = 0;

					for (int j=0; j < filter.length; j++) {
						final int fSizeID2 = filter[j].length/2;
						int v0 = v + j - fSizeJD2;
						if (v0 < 0) v0 = -v0;
						if (v0 >= inData.height) v0 = 2*inData.height - v0 - 1;

						for (int i=0; i < filter[j].length; i++) {
							int u0 = u + i - fSizeID2;
							if (u0 < 0) u0 = -u0;
							if (u0 >= inData.width) u0 = 2*inData.width - u0 - 1;

							sum += inData.getPixel(u0, v0)*filter[j][i];
						}
					}
					outData.setPixel(u, v, clamp8(offset + sum/den));
				}
			});
		} else if (imageType == Picsi.IMAGE_TYPE_RGB) {
			Parallel.For(0, outData.height, v -> {
				for (int u=0; u < outData.width; u++) {
					RGB sum = new RGB(0, 0, 0);

					for (int j=0; j < filter.length; j++) {
						final int fSizeID2 = filter[j].length/2;
						int v0 = v + j - fSizeJD2;
						if (v0 < 0) v0 = -v0;
						if (v0 >= inData.height) v0 = 2*inData.height - v0 - 1;

						for (int i=0; i < filter[j].length; i++) {
							int u0 = u + i - fSizeID2;
							if (u0 < 0) u0 = -u0;
							if (u0 >= inData.width) u0 = 2*inData.width - u0 - 1;

							RGB col = inData.palette.getRGB(inData.getPixel(u0, v0));
							sum.red += col.red*filter[j][i];
							sum.green += col.green*filter[j][i];
							sum.blue += col.blue*filter[j][i];
						}
					}
					sum.red = clamp8(offset + sum.red/den);
					sum.green = clamp8(offset + sum.green/den);
					sum.blue = clamp8(offset + sum.blue/den);
					outData.setPixel(u, v, outData.palette.getPixel(sum));
				}
			});
		}

		return outData;
	}

	/**
	 * Convolve inData with x-y separated filters
	 * @param inData
	 * @param imageType
	 * @param xFilter
	 * @param xDen denominator for filter normalization
	 * @param xOffset intensity offset
	 * @param yFilter
	 * @param yDen denominator for filter normalization
	 * @param yOffset intensity offset
	 * @return
	 */
	public static ImageData convolve(ImageData inData, int imageType, int[] xFilter, int xDen, int xOffset, int[] yFilter, int yDen, int yOffset) {
		int[][] filter = new int[1][xFilter.length];
		filter[0] = xFilter;

		// filter in x-direction
		ImageData outData = convolve(inData, imageType, filter, xDen, xOffset);

		filter = new int[yFilter.length][1];
		for(int i=0; i < yFilter.length; i++) filter[i][0] = yFilter[i];

		// filter in x-direction
		return convolve(outData, imageType, filter, yDen, yOffset);
	}
}

