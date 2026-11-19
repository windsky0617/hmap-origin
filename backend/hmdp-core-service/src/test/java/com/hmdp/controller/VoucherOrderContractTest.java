package com.hmdp.controller;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.hmdp.config.WebExceptionAdvice;
import com.hmdp.dto.Result;
import com.hmdp.dto.UserDTO;
import com.hmdp.service.IVoucherOrderService;
import com.hmdp.utils.LoginInterceptor;
import com.hmdp.utils.UserHolder;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class VoucherOrderContractTest {

    private MockMvc mockMvc;
    private IVoucherOrderService voucherOrderService;

    private final ObjectMapper objectMapper = new ObjectMapper();

    @BeforeEach
    void setUp() {
        UserHolder.removeUser();

        UserDTO user = new UserDTO();
        user.setId(1L);
        UserHolder.saveUser(user);

        voucherOrderService = mock(IVoucherOrderService.class);

        VoucherOrderController controller = new VoucherOrderController();
        ReflectionTestUtils.setField(
                controller,
                "voucherOrderService",
                voucherOrderService
        );

        mockMvc = MockMvcBuilders
                .standaloneSetup(controller)
                .setControllerAdvice(new WebExceptionAdvice())
                .addInterceptors(new LoginInterceptor())
                .build();
    }

    @AfterEach
    void tearDown() {
        UserHolder.removeUser();
    }

    @ParameterizedTest
    @ValueSource(longs = {
            1L,
            9007199254740991L,
            9007199254740993L,
            Long.MAX_VALUE
    })
    void orderIdIsAlwaysReturnedAsExactJsonString(long orderId)
            throws Exception {

        when(voucherOrderService.seckillVoucher(1L))
                .thenReturn(Result.ok(orderId));

        MvcResult response = mockMvc.perform(
                        post("/voucher-order/seckill/1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data")
                        .value(Long.toString(orderId)))
                .andReturn();

        JsonNode body = objectMapper.readTree(
                response.getResponse().getContentAsByteArray()
        );

        assertTrue(body.get("data").isTextual());
        assertEquals(
                Long.toString(orderId),
                body.get("data").textValue()
        );

        verify(voucherOrderService).seckillVoucher(1L);
    }

    @Test
    void existingStringOrderIdRemainsUnchanged() throws Exception {
        when(voucherOrderService.seckillVoucher(1L))
                .thenReturn(Result.ok("9007199254740993"));

        mockMvc.perform(post("/voucher-order/seckill/1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data")
                        .value("9007199254740993"));
    }

    @Test
    void businessFailureKeepsExistingResponseContract()
            throws Exception {

        when(voucherOrderService.seckillVoucher(1L))
                .thenReturn(Result.fail("库存不足"));

        mockMvc.perform(post("/voucher-order/seckill/1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.code").value(10000))
                .andExpect(jsonPath("$.errorMsg").value("库存不足"));
    }

    @Test
    void largeVoucherIdInPathReachesServiceExactly()
            throws Exception {

        long voucherId = 9007199254740993L;

        when(voucherOrderService.seckillVoucher(voucherId))
                .thenReturn(Result.ok(1L));

        mockMvc.perform(
                        post("/voucher-order/seckill/9007199254740993"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data").value("1"));

        verify(voucherOrderService).seckillVoucher(voucherId);
    }

    @Test
    void anonymousRequestReturns401BeforeCallingService()
            throws Exception {

        UserHolder.removeUser();

        mockMvc.perform(post("/voucher-order/seckill/1"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.code").value(40100));

        verifyNoInteractions(voucherOrderService);
    }

    @Test
    void invalidVoucherIdReturns400BeforeCallingService()
            throws Exception {

        mockMvc.perform(post("/voucher-order/seckill/abc"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.code").value(40000));

        verifyNoInteractions(voucherOrderService);
    }

    @Test
    void unexpectedServiceExceptionKeepsSanitized500()
            throws Exception {

        when(voucherOrderService.seckillVoucher(1L))
                .thenThrow(new IllegalStateException(
                        "internal-order-detail"
                ));

        mockMvc.perform(post("/voucher-order/seckill/1"))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.code").value(50000))
                .andExpect(jsonPath("$.errorMsg").value("服务器异常"));
    }
}