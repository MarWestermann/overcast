/* License added by: GRADLE-LICENSE-PLUGIN
 *
 * Copyright 2008-2012 XebiaLabs
 * 
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 * 
 *     http://www.apache.org/licenses/LICENSE-2.0
 * 
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.xebialabs.overcast;

import java.util.Date;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.amazonaws.auth.BasicAWSCredentials;
import com.amazonaws.services.ec2.AmazonEC2Client;
import com.amazonaws.services.ec2.model.CreateTagsRequest;
import com.amazonaws.services.ec2.model.DescribeInstancesRequest;
import com.amazonaws.services.ec2.model.Instance;
import com.amazonaws.services.ec2.model.Placement;
import com.amazonaws.services.ec2.model.RunInstancesRequest;
import com.amazonaws.services.ec2.model.RunInstancesResult;
import com.amazonaws.services.ec2.model.Tag;
import com.amazonaws.services.ec2.model.TerminateInstancesRequest;

import static com.google.common.collect.Lists.newArrayList;
import static com.xebialabs.overcast.OvercastProperties.getOvercastProperty;
import static com.xebialabs.overcast.OvercastProperties.getRequiredOvercastProperty;

class Ec2CloudHost implements CloudHost {

	public static final String AMI_AVAILABILITY_ZONE_PROPERTY_SUFFIX = ".amiAvailabilityZone";
	public static final String AMI_BOOT_SECONDS_PROPERTY_SUFFIX = ".amiBootSeconds";
	public static final String AMI_ID_PROPERTY_SUFFIX = ".amiId";
	public static final String AMI_INSTANCE_TYPE_PROPERTY_SUFFIX = ".amiInstanceType";
	public static final String AMI_KEY_NAME_PROPERTY_SUFFIX = ".amiKeyName";
	public static final String AMI_SECURITY_GROUP_PROPERTY_SUFFIX = ".amiSecurityGroup";
	public static final String AWS_ACCESS_KEY_PROPERTY = "aws.accessKey";
	public static final String AWS_ENDPOINT_DEFAULT = "https://ec2.amazonaws.com";
	public static final String AWS_ENDPOINT_PROPERTY = "aws.endpoint";
	public static final String AWS_SECRET_KEY_PROPERTY = "aws.secretKey";

	private final String hostLabel;
	private final String amiId;
	private final String awsEndpointURL;
	private final String awsAccessKey;
	private final String awsSecretKey;
	private final String amiAvailabilityZone;
	private final String amiInstanceType;
	private final String amiSecurityGroup;
	private final String amiKeyName;
	private final int amiBootSeconds;

	private AmazonEC2Client ec2;
	private String instanceId;
	private String publicDnsAddress;

	public Ec2CloudHost(String hostLabel, String amiId) {
		this.hostLabel = hostLabel;
		this.amiId = amiId;
		this.awsEndpointURL = getOvercastProperty(AWS_ENDPOINT_PROPERTY, AWS_ENDPOINT_DEFAULT);
		this.awsAccessKey = getRequiredOvercastProperty(AWS_ACCESS_KEY_PROPERTY);
		this.awsSecretKey = getRequiredOvercastProperty(AWS_SECRET_KEY_PROPERTY);
		this.amiAvailabilityZone = getOvercastProperty(hostLabel + AMI_AVAILABILITY_ZONE_PROPERTY_SUFFIX, null);
		this.amiInstanceType = getRequiredOvercastProperty(hostLabel + AMI_INSTANCE_TYPE_PROPERTY_SUFFIX);
		this.amiSecurityGroup = getRequiredOvercastProperty(hostLabel + AMI_SECURITY_GROUP_PROPERTY_SUFFIX);
		this.amiKeyName = getRequiredOvercastProperty(hostLabel + AMI_KEY_NAME_PROPERTY_SUFFIX);
		this.amiBootSeconds = Integer.valueOf(getRequiredOvercastProperty(hostLabel + AMI_BOOT_SECONDS_PROPERTY_SUFFIX));

		ec2 = new AmazonEC2Client(new BasicAWSCredentials(awsAccessKey, awsSecretKey));
		ec2.setEndpoint(awsEndpointURL);
	}

	@Override
	public void setup() {
		instanceId = runInstance();

		publicDnsAddress = waitUntilRunningAndGetPublicDnsName();

		setInstanceName();

		waitForAmiBoot();
	}

	@Override
	public void teardown() {
		ec2.terminateInstances(new TerminateInstancesRequest(newArrayList(instanceId)));
	}

	@Override
	public String getHostName() {
		return publicDnsAddress;
	}

	@Override
	public int getPort(int port) {
		return port;
	}

	protected String runInstance() {
		RunInstancesRequest run = new RunInstancesRequest(amiId, 1, 1);
		run.withInstanceInitiatedShutdownBehavior("terminate");
		if (amiInstanceType != null) {
			run.withInstanceType(amiInstanceType);
		}
		if (amiSecurityGroup != null) {
			run.withSecurityGroups(amiSecurityGroup);
		}
		if (amiKeyName != null) {
			run.withKeyName(amiKeyName);
		}
		if (amiAvailabilityZone != null) {
			run.withPlacement(new Placement(amiAvailabilityZone));
		}
		
		RunInstancesResult result = ec2.runInstances(run);
		
		return result.getReservation().getInstances().get(0).getInstanceId();
	}

	protected void setInstanceName() {
		ec2.createTags(new CreateTagsRequest(newArrayList(instanceId), newArrayList(new Tag("Name", hostLabel + " started at " + new Date()))));
	}

	public String waitUntilRunningAndGetPublicDnsName() {
		// Give Amazon some time to settle before we ask it for information
		sleep(5);

		for (;;) {
			DescribeInstancesRequest describe = new DescribeInstancesRequest().withInstanceIds(newArrayList(instanceId));
			Instance instance = ec2.describeInstances(describe).getReservations().get(0).getInstances().get(0);
			if (instance.getState().getName().equals("running")) {
				return instance.getPublicDnsName();
			}

			logger.info("Instance {} is still {}. Waiting...", instanceId, instance.getState().getName());
			sleep(1);
		}

	}

	protected void waitForAmiBoot() {
		logger.info("Waiting {} seconds for the image to finish booting", amiBootSeconds);
		sleep(amiBootSeconds);
	}

	private static void sleep(final int seconds) {
		try {
			Thread.sleep(seconds * 1000);
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
		}
	}

	private static final Logger logger = LoggerFactory.getLogger(Ec2CloudHost.class);

}