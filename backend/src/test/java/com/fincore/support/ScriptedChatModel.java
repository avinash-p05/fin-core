package com.fincore.support;

import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.metadata.ChatResponseMetadata;
import org.springframework.ai.chat.metadata.DefaultUsage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.model.tool.ToolCallingChatOptions;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.execution.ToolExecutionException;

/**
 * A fake LLM for tests. Each test sets a script that plays the model: it calls the tool callbacks
 * Spring AI attached to the prompt (so the real @Tool wiring and JSON argument binding run) and
 * returns the text the "model" answers with.
 */
public class ScriptedChatModel implements ChatModel {

    /** Tool access for a script, as the model would see it. */
    public static final class Tools {
        private final Map<String, ToolCallback> byName;
        public final Prompt prompt;

        Tools(Prompt prompt, List<ToolCallback> callbacks) {
            this.prompt = prompt;
            this.byName = callbacks.stream()
                    .collect(Collectors.toMap(c -> c.getToolDefinition().name(), Function.identity()));
        }

        /** Like Spring AI's tool-calling loop: a tool failure comes back as text, not an exception. */
        public String call(String tool, String jsonArgs) {
            ToolCallback cb = byName.get(tool);
            if (cb == null) {
                throw new IllegalArgumentException("No tool " + tool + "; have " + byName.keySet());
            }
            try {
                return cb.call(jsonArgs);
            } catch (ToolExecutionException e) {
                return "ERROR: " + e.getMessage();
            }
        }

        public java.util.Set<String> names() {
            return byName.keySet();
        }
    }

    @FunctionalInterface
    public interface Script {
        String answer(Tools tools) throws Exception;
    }

    private volatile Script script = tools -> "{}";

    public void script(Script script) {
        this.script = script;
    }

    @Override
    public ChatOptions getOptions() {
        return ToolCallingChatOptions.builder().build();
    }

    @Override
    public ChatResponse call(Prompt prompt) {
        List<ToolCallback> callbacks = prompt.getOptions() instanceof ToolCallingChatOptions o && o.getToolCallbacks() != null
                ? o.getToolCallbacks() : List.of();
        String text;
        try {
            text = script.answer(new Tools(prompt, callbacks));
        } catch (RuntimeException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
        return new ChatResponse(List.of(new Generation(new AssistantMessage(text))),
                ChatResponseMetadata.builder().model("scripted-test-model").usage(new DefaultUsage(120, 80)).build());
    }
}
