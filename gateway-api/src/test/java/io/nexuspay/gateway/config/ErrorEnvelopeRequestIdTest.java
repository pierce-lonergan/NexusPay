package io.nexuspay.gateway.config;

import io.nexuspay.common.exception.PaymentException;
import io.nexuspay.gateway.adapter.in.filter.CorrelationIdFilter;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * TEST-6 (F3): the error-envelope BODY carries {@code request_id} for body-only correlation, and it equals
 * the {@code X-Request-Id} RESPONSE header (proving the body sources MDC — the same value the
 * {@link CorrelationIdFilter} set). Two cases: an inbound {@code X-Request-Id} is echoed verbatim into BOTH
 * the response header and the body; with no inbound header, the filter generates one and BOTH carry it.
 *
 * <p>F3 needs no production change — {@code GlobalExceptionHandler.requestId()} already reads MDC and
 * {@code ApiError} already serializes {@code request_id}. This test closes the gap by asserting it.</p>
 */
class ErrorEnvelopeRequestIdTest {

    private MockMvc mockMvc;
    private final ObjectMapper json = new ObjectMapper();

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.standaloneSetup(new ThrowingController())
                .setControllerAdvice(new GlobalExceptionHandler())
                .addFilter(new CorrelationIdFilter(), "/*")
                .build();
    }

    @Test
    void inboundRequestId_isEchoedInBothHeaderAndBody() throws Exception {
        String inbound = "rid-inbound-7f3a";

        MvcResult result = mockMvc.perform(get("/boom")
                        .header(CorrelationIdFilter.REQUEST_ID_HEADER, inbound))
                .andExpect(status().isNotFound())
                .andReturn();

        String headerRid = result.getResponse().getHeader(CorrelationIdFilter.REQUEST_ID_HEADER);
        JsonNode body = json.readTree(result.getResponse().getContentAsString());
        String bodyRid = body.path("error").path("request_id").asText();

        assertThat(headerRid).isEqualTo(inbound);
        assertThat(bodyRid).isEqualTo(inbound);
        assertThat(bodyRid).isEqualTo(headerRid); // body sources the same correlation id as the header
    }

    @Test
    void generatedRequestId_isNonBlank_andBodyEqualsResponseHeader() throws Exception {
        MvcResult result = mockMvc.perform(get("/boom"))
                .andExpect(status().isNotFound())
                .andReturn();

        String headerRid = result.getResponse().getHeader(CorrelationIdFilter.REQUEST_ID_HEADER);
        JsonNode body = json.readTree(result.getResponse().getContentAsString());
        String bodyRid = body.path("error").path("request_id").asText();

        assertThat(headerRid).isNotBlank();
        assertThat(bodyRid).isNotBlank();
        assertThat(bodyRid).isEqualTo(headerRid);
    }

    @RestController
    static class ThrowingController {
        @GetMapping("/boom")
        String boom() {
            throw PaymentException.notFound("pay_x");
        }
    }
}
