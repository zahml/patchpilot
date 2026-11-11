package com.patchpilot.backend;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Assertions;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationContext;
import org.springframework.boot.test.context.SpringBootTest;
import com.patchpilot.backend.review.OpenAiCompatibleReviewModelClient;
import com.patchpilot.backend.review.ReviewModelClient;

@SpringBootTest(properties = {
	"patchpilot.model.provider=openai",
	"patchpilot.model.base-url=http://127.0.0.1:18081/v1",
	"patchpilot.model.api-key=test-key",
	"patchpilot.model.name=test-model",
	"patchpilot.messaging.enabled=false"
})
class PatchPilotBackendApplicationTests {

	@Autowired
	private ApplicationContext applicationContext;

	@Test
	void contextLoads() {
	}

	@Test
	void createsTheConfiguredOpenAiCompatibleModelClient() {
		Assertions.assertInstanceOf(OpenAiCompatibleReviewModelClient.class,
				applicationContext.getBean(ReviewModelClient.class));
	}

}
