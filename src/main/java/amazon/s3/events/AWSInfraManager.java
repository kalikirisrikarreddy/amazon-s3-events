package amazon.s3.events;

import java.io.File;

import com.amazonaws.auth.AWSCredentialsProvider;
import com.amazonaws.auth.AWSStaticCredentialsProvider;
import com.amazonaws.auth.BasicAWSCredentials;
import com.amazonaws.services.identitymanagement.AmazonIdentityManagement;
import com.amazonaws.services.identitymanagement.AmazonIdentityManagementClientBuilder;
import com.amazonaws.services.identitymanagement.model.AttachRolePolicyRequest;
import com.amazonaws.services.identitymanagement.model.CreateRoleRequest;
import com.amazonaws.services.identitymanagement.model.EntityAlreadyExistsException;
import com.amazonaws.services.identitymanagement.model.GetRoleRequest;
import com.amazonaws.services.identitymanagement.model.GetRoleResult;
import com.amazonaws.services.s3.AmazonS3;
import com.amazonaws.services.s3.AmazonS3ClientBuilder;

import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.AwsCredentialsProvider;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.lambda.LambdaClient;
import software.amazon.awssdk.services.lambda.model.AddPermissionRequest;
import software.amazon.awssdk.services.lambda.model.CreateFunctionRequest;
import software.amazon.awssdk.services.lambda.model.CreateFunctionResponse;
import software.amazon.awssdk.services.lambda.model.FunctionCode;
import software.amazon.awssdk.services.lambda.model.GetFunctionRequest;
import software.amazon.awssdk.services.lambda.model.GetFunctionResponse;
import software.amazon.awssdk.services.lambda.model.PackageType;
import software.amazon.awssdk.services.lambda.model.ResourceNotFoundException;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.Event;
import software.amazon.awssdk.services.s3.model.LambdaFunctionConfiguration;
import software.amazon.awssdk.services.s3.model.NotificationConfiguration;
import software.amazon.awssdk.services.s3.model.PutBucketNotificationConfigurationRequest;

public class AWSInfraManager {

	static BasicAWSCredentials basicAWSCredentials = new BasicAWSCredentials("DUMMY", "DUMMY");

	static AwsBasicCredentials basicAWSCredentialsV2 = AwsBasicCredentials.create("DUMMY", "DUMMY");

	static AWSCredentialsProvider awsCredentialsProvider = new AWSStaticCredentialsProvider(basicAWSCredentials);

	static AwsCredentialsProvider awsCredentialsProviderV2 = StaticCredentialsProvider.create(basicAWSCredentialsV2);

	static AmazonIdentityManagement iam = AmazonIdentityManagementClientBuilder.standard().withRegion("us-east-1")
			.withCredentials(awsCredentialsProvider).build();

	static AmazonS3 s3 = AmazonS3ClientBuilder.standard().withRegion("us-east-1")
			.withCredentials(awsCredentialsProvider).build();

	static S3Client s3ClientV2 = S3Client.builder().region(Region.US_EAST_1)
			.credentialsProvider(awsCredentialsProviderV2).build();

	static LambdaClient lambda = LambdaClient.builder().region(Region.US_EAST_1)
			.credentialsProvider(awsCredentialsProviderV2).build();

	public static void main(String[] args) throws InterruptedException {
		String roleArn = createExecutionRoleForLambda();
		createNecessaryBuckets();
		pushJarToS3();
		String lambdaArn = createLambda(roleArn);
		enableS3Events(lambdaArn);
		pushImageToS3();
	}

	private static void enableS3Events(String lambdaArn) {
		s3ClientV2.putBucketNotificationConfiguration(PutBucketNotificationConfigurationRequest.builder()
				.bucket(Constants.INPUT_BUCKET_NAME)
				.notificationConfiguration(NotificationConfiguration.builder()
						.lambdaFunctionConfigurations(LambdaFunctionConfiguration.builder()
								.events(Event.S3_OBJECT_CREATED_PUT).lambdaFunctionArn(lambdaArn).id("ID-1").build())
						.build())
				.build());
	}

	private static void pushImageToS3() throws InterruptedException {
		s3.putObject(Constants.INPUT_BUCKET_NAME, "s3.png", new File("s3.png"));
		System.out.println("Wait for 5 minutes...!");
		Thread.sleep(300000);
		System.out.println("Wait ended");
		System.out.println("Did we succeed ? " + s3.doesObjectExist(Constants.OUTPUT_BUCKET_NAME, "blurred-s3.png"));
	}

	private static String createLambda(String roleArn) {
		GetFunctionRequest getFunctionRequest = GetFunctionRequest.builder().functionName("image-blur-lambda").build();
		GetFunctionResponse getFunctionResponse = null;
		try {
			getFunctionResponse = lambda.getFunction(getFunctionRequest);
			return getFunctionResponse.configuration().functionArn();
		} catch (ResourceNotFoundException e) {
			FunctionCode functionCode = FunctionCode.builder().s3Bucket(Constants.LAMBDA_CODE_BUCKET_NAME)
					.s3Key("amazon-s3-events-1.0.0-shaded.jar").build();
			CreateFunctionRequest createFunctionRequest = CreateFunctionRequest.builder()
					.functionName("image-blur-lambda").memorySize(256)
					.runtime(software.amazon.awssdk.services.lambda.model.Runtime.JAVA17)
					.handler("amazon.s3.events.ImageBlur::handleRequest").packageType(PackageType.ZIP).timeout(300)
					.code(functionCode).role(roleArn).build();
			CreateFunctionResponse createFunctionResponse = lambda.createFunction(createFunctionRequest);
			String lambdaArn = createFunctionResponse.functionArn();
			AddPermissionRequest addPermissionRequest = AddPermissionRequest.builder().functionName("image-blur-lambda")
					.statementId("ID-1").action("lambda:InvokeFunction").principal("s3.amazonaws.com")
					.sourceArn("arn:aws:s3:::" + Constants.INPUT_BUCKET_NAME).build();
			lambda.addPermission(addPermissionRequest);
			return lambdaArn;
		}

	}

	private static void pushJarToS3() {
		s3.putObject(Constants.LAMBDA_CODE_BUCKET_NAME, "amazon-s3-events-1.0.0-shaded.jar",
				new File("target/amazon-s3-events-1.0.0-shaded.jar"));
	}

	private static void createNecessaryBuckets() {
		if (!s3.doesBucketExistV2(Constants.LAMBDA_CODE_BUCKET_NAME))
			s3.createBucket(Constants.LAMBDA_CODE_BUCKET_NAME);

		if (!s3.doesBucketExistV2(Constants.INPUT_BUCKET_NAME))
			s3.createBucket(Constants.INPUT_BUCKET_NAME);

		if (!s3.doesBucketExistV2(Constants.OUTPUT_BUCKET_NAME))
			s3.createBucket(Constants.OUTPUT_BUCKET_NAME);
	}

	private static String createExecutionRoleForLambda() {
		String roleArn = null;
		CreateRoleRequest createRoleRequest = new CreateRoleRequest();
		createRoleRequest.setRoleName("lambda-exec-role");
		createRoleRequest.setAssumeRolePolicyDocument("""
				{
				    "Version": "2012-10-17",
				    "Statement": [
				        {
				            "Effect": "Allow",
				            "Principal": {
				                "Service": "lambda.amazonaws.com"
				            },
				            "Action": "sts:AssumeRole"
				        }
				    ]
				}
				""");
		try {
			roleArn = iam.createRole(createRoleRequest).getRole().getArn();
		} catch (EntityAlreadyExistsException e) {
			System.out.println("Role seems to be there, thus creation failed");
			return getRole().getRole().getArn();
		}

		// attach cloudwatch logs access policy to role created earlier
		AttachRolePolicyRequest attachRolePolicyRequest1 = new AttachRolePolicyRequest();
		attachRolePolicyRequest1.setPolicyArn("arn:aws:iam::aws:policy/CloudWatchLogsFullAccess");
		attachRolePolicyRequest1.setRoleName("lambda-exec-role");
		iam.attachRolePolicy(attachRolePolicyRequest1);

		// attach s3 access policy to role created earlier
		AttachRolePolicyRequest attachRolePolicyRequest2 = new AttachRolePolicyRequest();
		attachRolePolicyRequest2.setPolicyArn("arn:aws:iam::aws:policy/AmazonS3FullAccess");
		attachRolePolicyRequest2.setRoleName("lambda-exec-role");
		iam.attachRolePolicy(attachRolePolicyRequest2);

		return roleArn;

	}

	private static GetRoleResult getRole() {
		GetRoleRequest getRoleRequest = new GetRoleRequest();
		getRoleRequest.setRoleName("lambda-exec-role");
		return iam.getRole(getRoleRequest);
	}

}
