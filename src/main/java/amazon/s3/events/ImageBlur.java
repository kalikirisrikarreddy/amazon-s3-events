package amazon.s3.events;

import java.awt.Color;
import java.awt.image.BufferedImage;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;

import javax.imageio.ImageIO;

import com.amazonaws.SdkClientException;
import com.amazonaws.services.lambda.runtime.Context;
import com.amazonaws.services.lambda.runtime.RequestHandler;
import com.amazonaws.services.lambda.runtime.events.S3Event;
import com.amazonaws.services.s3.AmazonS3;
import com.amazonaws.services.s3.AmazonS3ClientBuilder;

public class ImageBlur implements RequestHandler<S3Event, String> {

	AmazonS3 amazonS3 = AmazonS3ClientBuilder.standard().withRegion("us-east-1").build();

	@Override
	public String handleRequest(S3Event input, Context context) {
		input.getRecords().stream().filter(rec -> rec.getEventName().equals("ObjectCreated:Put")).forEach(rec -> {
			String srcObjKey = rec.getS3().getObject().getUrlDecodedKey();
			String dstObjKey = "blurred-" + srcObjKey;
			System.out.println("source object key : " + srcObjKey);
			System.out.println("destination object key : " + dstObjKey);
			try {
				int index = 0, max = 400, radius = 10, a1 = 0, r = 0, g = 0, b = 0, x = 1, y = 1, x1, y1, d = 0;
				Color colors[] = new Color[max];
				FileOutputStream fos = new FileOutputStream(new File("/tmp/" + srcObjKey));
				amazonS3.listObjects(Constants.INPUT_BUCKET_NAME).getObjectSummaries().forEach(s -> {
					System.out.println(s.getKey() + " - " + s.getSize());
				});
				fos.write(amazonS3.getObject(Constants.INPUT_BUCKET_NAME, srcObjKey).getObjectContent().readAllBytes());
				fos.close();
				BufferedImage inputBufferedImage = ImageIO.read(new File("/tmp/" + srcObjKey));
				System.out.println("read the input file");
				BufferedImage outputBufferedImage = new BufferedImage(inputBufferedImage.getWidth(),
						inputBufferedImage.getHeight(), BufferedImage.TYPE_INT_RGB);
				for (x = radius; x < inputBufferedImage.getHeight() - radius; x++) {
					for (y = radius; y < inputBufferedImage.getWidth() - radius; y++) {
						for (x1 = x - radius; x1 < x + radius; x1++) {
							for (y1 = y - radius; y1 < y + radius; y1++) {
								colors[index++] = new Color(inputBufferedImage.getRGB(y1, x1));
							}
						}
						index = 0;
						for (d = 0; d < max; d++) {
							a1 = a1 + colors[d].getAlpha();
						}
						a1 = a1 / (max);
						for (d = 0; d < max; d++) {
							r = r + colors[d].getRed();
						}
						r = r / (max);
						for (d = 0; d < max; d++) {
							g = g + colors[d].getGreen();
						}
						g = g / (max);
						for (d = 0; d < max; d++) {
							b = b + colors[d].getBlue();
						}
						b = b / (max);
						int sum1 = (a1 << 24) + (r << 16) + (g << 8) + b;
						outputBufferedImage.setRGB(y, x, (sum1));
					}
				}
				ImageIO.write(outputBufferedImage, "png", new File("/tmp/" + dstObjKey));
				System.out.println("Finished generating blurred content file");
				amazonS3.putObject(Constants.OUTPUT_BUCKET_NAME, dstObjKey, new File("/tmp/" + dstObjKey));
				System.out.println("Finished uploading blurred content file");
			} catch (SdkClientException | IOException e) {
				e.printStackTrace();
			}
		});
		return null;
	}
}