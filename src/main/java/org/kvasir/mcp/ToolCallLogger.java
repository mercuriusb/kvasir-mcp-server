package org.kvasir.mcp;

import java.lang.reflect.Method;
import java.lang.reflect.Parameter;
import java.util.StringJoiner;

import org.jboss.logging.Logger;

import io.quarkiverse.mcp.server.Tool;
import io.quarkiverse.mcp.server.ToolArg;
import jakarta.annotation.Priority;
import jakarta.interceptor.AroundInvoke;
import jakarta.interceptor.Interceptor;
import jakarta.interceptor.InvocationContext;

/**
 * Logs every invocation of an MCP tool annotated with {@link LoggedToolCall}, including the tool
 * name and the arguments it was called with.
 * <p>
 * The name taken from the {@link Tool} annotation is logged (that is {@code list_versions}, not
 * {@code listVersions}), so that log entries map directly onto what the agent requested over the
 * MCP protocol.
 */
@LoggedToolCall
@Interceptor
@Priority(Interceptor.Priority.APPLICATION)
public class ToolCallLogger {

    private static final Logger LOG = Logger.getLogger(ToolCallLogger.class);

    @AroundInvoke
    Object logToolCall(InvocationContext context) throws Exception {
        LOG.infof("MCP tool invoked: %s(%s)", toolName(context.getMethod()), arguments(context));
        return context.proceed();
    }

    private static String toolName(Method method) {
        Tool tool = method.getAnnotation(Tool.class);
        // Without an explicit name() the annotation holds the ELEMENT_NAME sentinel and the
        // extension derives the tool name from the method name.
        return tool == null || Tool.ELEMENT_NAME.equals(tool.name()) ? method.getName() : tool.name();
    }

    private static String arguments(InvocationContext context) {
        Parameter[] parameters = context.getMethod().getParameters();
        Object[] values = context.getParameters();
        StringJoiner joiner = new StringJoiner(", ");
        for (int i = 0; i < parameters.length; i++) {
            joiner.add(parameterName(parameters[i]) + "=" + values[i]);
        }
        return joiner.toString();
    }

    private static String parameterName(Parameter parameter) {
        ToolArg arg = parameter.getAnnotation(ToolArg.class);
        return arg == null || ToolArg.ELEMENT_NAME.equals(arg.name()) ? parameter.getName() : arg.name();
    }
}
