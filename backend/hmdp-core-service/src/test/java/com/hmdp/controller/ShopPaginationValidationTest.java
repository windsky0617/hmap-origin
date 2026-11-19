package com.hmdp.controller;

import com.baomidou.mybatisplus.extension.conditions.query.QueryChainWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.hmdp.config.WebExceptionAdvice;
import com.hmdp.dto.Result;
import com.hmdp.entity.Shop;
import com.hmdp.service.IShopService;
import com.hmdp.utils.LoginInterceptor;
import com.hmdp.utils.SystemConstants;
import com.hmdp.utils.UserHolder;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.validation.beanvalidation.LocalValidatorFactoryBean;

import java.util.Collections;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class ShopPaginationValidationTest {

    private MockMvc mockMvc;
    private IShopService shopService;
    private LocalValidatorFactoryBean validator;

    @BeforeEach
    void setUp() {
        UserHolder.removeUser();

        shopService = mock(IShopService.class);

        ShopController controller = new ShopController();
        ReflectionTestUtils.setField(
                controller, "shopService", shopService
        );

        validator = new LocalValidatorFactoryBean();
        validator.afterPropertiesSet();

        mockMvc = MockMvcBuilders
                .standaloneSetup(controller)
                .setValidator(validator)
                .setControllerAdvice(new WebExceptionAdvice())
                .addInterceptors(new LoginInterceptor())
                .build();
    }

    @AfterEach
    void tearDown() {
        validator.close();
        UserHolder.removeUser();
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "0",
            "-1",
            "429496730",
            "2147483647",
            "abc"
    })
    void invalidTypePageReturns400BeforeCallingService(String current)
            throws Exception {

        mockMvc.perform(get("/shop/of/type")
                        .param("typeId", "1")
                        .param("current", current)
                        .param("x", "120.14")
                        .param("y", "30.33"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.code").value(40000))
                .andExpect(jsonPath("$.errorMsg").value("请求参数错误"));

        verifyNoInteractions(shopService);
    }

    @ParameterizedTest
    @ValueSource(ints = {1, 429496729})
    void typePageBoundaryReachesServiceUnchanged(int current)
            throws Exception {

        when(shopService.queryShopByType(1, current, null, null))
                .thenReturn(Result.ok(Collections.emptyList()));

        mockMvc.perform(get("/shop/of/type")
                        .param("typeId", "1")
                        .param("current", Integer.toString(current)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data").isArray());

        verify(shopService).queryShopByType(
                1, current, null, null
        );
    }

    @Test
    void missingTypePageDefaultsToOneAndKeepsCoordinates()
            throws Exception {

        when(shopService.queryShopByType(1, 1, 120.14, 30.33))
                .thenReturn(Result.ok(Collections.emptyList()));

        mockMvc.perform(get("/shop/of/type")
                        .param("typeId", "1")
                        .param("x", "120.14")
                        .param("y", "30.33"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.code").value(0));

        verify(shopService).queryShopByType(
                1, 1, 120.14, 30.33
        );
    }

    @ParameterizedTest
    @ValueSource(strings = {"0", "-1", "abc"})
    void invalidNamePageReturns400BeforeQuerying(String current)
            throws Exception {

        mockMvc.perform(get("/shop/of/name")
                        .param("current", current))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.code").value(40000));

        verifyNoInteractions(shopService);
    }

    @Test
    @SuppressWarnings({"rawtypes", "unchecked"})
    void missingNamePageDefaultsToOneAndKeepsPageSize()
            throws Exception {

        QueryChainWrapper<Shop> query = mock(QueryChainWrapper.class);

        when(shopService.query()).thenReturn(query);
        when(query.like(false, "name", null)).thenReturn(query);

        when(query.page(any(Page.class))).thenAnswer(invocation -> {
            Page<Shop> page = invocation.getArgument(0);

            assertEquals(1L, page.getCurrent());
            assertEquals(
                    (long) SystemConstants.MAX_PAGE_SIZE,
                    page.getSize()
            );

            page.setRecords(Collections.emptyList());
            return page;
        });

        mockMvc.perform(get("/shop/of/name"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data").isArray());
    }
}