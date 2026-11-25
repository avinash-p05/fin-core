package com.fincore.ai;

import static org.assertj.core.api.Assertions.assertThat;

import com.fincore.support.IntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.openai.OpenAiChatModel;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationContext;
import org.springframework.test.context.TestPropertySource;

/**
 * FINCORE_COPILOT_PROVIDER=openai selects OpenAI as the copilot's model, and the OpenAI starter's
 * other models (embedding, image, audio, moderation) stay off. No request is sent to OpenAI.
 */
@TestPropertySource(properties = {"spring.ai.model.chat=openai", "spring.ai.openai.api-key=test-key-not-real"})
class CopilotProviderConfigTest extends IntegrationTest {

    @Autowired
    ApplicationContext context;

    @Test
    void openAiIsTheOnlyModel() {
        assertThat(context.getBeansOfType(ChatModel.class).values())
                .singleElement().isInstanceOf(OpenAiChatModel.class);
        assertThat(context.getBeanNamesForType(org.springframework.ai.embedding.EmbeddingModel.class)).isEmpty();
        assertThat(context.getBeanNamesForType(org.springframework.ai.image.ImageModel.class)).isEmpty();
        assertThat(context.getBeanNamesForType(org.springframework.ai.moderation.ModerationModel.class)).isEmpty();
    }

    @Test
    void defaultModelIsGpt5Mini() {
        OpenAiChatModel model = context.getBean(OpenAiChatModel.class);
        assertThat(model.getDefaultOptions().getModel()).isEqualTo("gpt-5-mini");
    }
}
