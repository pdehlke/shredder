/*
 * Copyright 2019 Adobe Systems Incorporated. All rights reserved.
 * This file is licensed to you under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License. You may obtain a copy
 * of the License at http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software distributed under
 * the License is distributed on an "AS IS" BASIS, WITHOUT WARRANTIES OR REPRESENTATIONS
 * OF ANY KIND, either express or implied. See the License for the specific language
 * governing permissions and limitations under the License.
 */

package com.adobe.aam.shredder.core.aws.queue;

import com.amazonaws.services.sqs.AmazonSQS;
import com.amazonaws.services.sqs.model.AmazonSQSException;
import com.amazonaws.services.sqs.model.CreateQueueRequest;
import com.amazonaws.services.sqs.model.QueueAttributeName;
import com.github.rholder.retry.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;

public class AwsQueueCreator implements QueueCreator {

    private static final Logger LOG = LoggerFactory.getLogger(AwsQueueCreator.class);

    private final AmazonSQS sqs;

    public AwsQueueCreator(AmazonSQS sqs) {
        this.sqs = sqs;
    }

    @Override
    public void createQueue(String queueName) {
        try {
            Retryer<Boolean> retryer = RetryerBuilder.<Boolean>newBuilder()
                    .retryIfException()
                    .withWaitStrategy(WaitStrategies.fixedWait(61, TimeUnit.SECONDS))
                    .withStopStrategy(StopStrategies.neverStop())
                    .withRetryListener(new RetryListener() {
                        @Override
                        public <V> void onRetry(Attempt<V> attempt) {
                            if (attempt.hasException()) {
                                LOG.info("Retrying to create queue in 61 seconds. {}", attempt.getExceptionCause().getMessage());
                            }
                        }
                    })
                    .build();

            CreateQueueRequest createQueueRequest = getCreateQueueRequest(queueName);

            LOG.info("Creating queue: {}", queueName);
            retryer.call(getCreateQueueTask(createQueueRequest));
            LOG.info("Created queue: {}", queueName);
        } catch (RetryException | ExecutionException e) {
            LOG.error("Received error while trying to create queue", e);
            throw new RuntimeException(e);
        }
    }

    public void deleteQueue(String queueName) {
        LOG.info("Deleting SQS queue: {}", queueName);
        sqs.deleteQueue(sqs.getQueueUrl(queueName).getQueueUrl());
    }


    private Callable<Boolean> getCreateQueueTask(CreateQueueRequest createQueueRequest) {
        return () -> {
            try {
                LOG.info("Executing create queue operation: {}", createQueueRequest.getQueueName());
                sqs.createQueue(createQueueRequest);
            } catch (AmazonSQSException e) {
                if (!e.getErrorCode().equals("QueueAlreadyExists")) {
                    throw e;
                }
            }

            return true;
        };
    }

    private static CreateQueueRequest getCreateQueueRequest(String queueName) {

        return new CreateQueueRequest()
                .withQueueName(queueName)
                .addAttributesEntry(QueueAttributeName.VisibilityTimeout.toString(), "3600")
                .addAttributesEntry(QueueAttributeName.ReceiveMessageWaitTimeSeconds.toString(), "20");
    }
}
