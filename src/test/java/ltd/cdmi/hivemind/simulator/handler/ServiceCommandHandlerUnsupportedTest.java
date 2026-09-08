package ltd.cdmi.hivemind.simulator.handler;

import ltd.cdmi.hivemind.simulator.diagnostic.DiagnosticCode;
import ltd.cdmi.hivemind.simulator.diagnostic.DiagnosticLogRecorder;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Services 未覆盖方法必须拒绝，不能用 result=0 占位。 */
class ServiceCommandHandlerUnsupportedTest {

    @Test
    void unknownMethodReturnsStableFailureAndS2Diagnostic() throws Exception {
        DiagnosticLogRecorder diagnostics = new DiagnosticLogRecorder();
        ServiceCommandHandler handler = newHandler(diagnostics);

        Map<String, Object> result = route(handler, "method_not_in_catalog");

        assertEquals(1, result.get("result"));
        assertTrue(diagnostics.getLogs().stream().anyMatch(log ->
                DiagnosticCode.SIMULATOR_METHOD_NOT_IMPLEMENTED.code().equals(log.get("code"))));
    }

    @Test
    void registeredMethodWithoutHandlerReturnsFailure() throws Exception {
        DiagnosticLogRecorder diagnostics = new DiagnosticLogRecorder();
        ServiceCommandHandler handler = newHandler(diagnostics);

        Map<String, Object> result = route(handler, "flighttask_prepare");

        assertEquals(1, result.get("result"));
        assertEquals(1, diagnostics.getLogs().size());
    }

    private static Map<String, Object> route(ServiceCommandHandler handler, String method) throws Exception {
        Method route = ServiceCommandHandler.class.getDeclaredMethod(
                "routeCommand", String.class, com.fasterxml.jackson.databind.JsonNode.class, String.class);
        route.setAccessible(true);
        @SuppressWarnings("unchecked")
        Map<String, Object> result = (Map<String, Object>) route.invoke(handler, method,
                new com.fasterxml.jackson.databind.ObjectMapper().createObjectNode(), "bid-test");
        return result;
    }

    private static ServiceCommandHandler newHandler(DiagnosticLogRecorder diagnostics) throws Exception {
        Constructor<?> constructor = ServiceCommandHandler.class.getDeclaredConstructors()[0];
        Object[] args = new Object[constructor.getParameterCount()];
        Class<?>[] types = constructor.getParameterTypes();
        for (int i = 0; i < types.length; i++) {
            if (types[i].equals(DiagnosticLogRecorder.class)) {
                args[i] = diagnostics;
            } else if (types[i].equals(AuthFlowHandler.class)) {
                args[i] = Mockito.mock(AuthFlowHandler.class);
            } else if (types[i].equals(PayloadControlHandler.class)) {
                args[i] = Mockito.mock(PayloadControlHandler.class);
            } else {
                args[i] = null;
            }
        }
        return (ServiceCommandHandler) constructor.newInstance(args);
    }
}
