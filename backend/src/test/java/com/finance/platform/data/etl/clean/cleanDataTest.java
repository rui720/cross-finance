package com.finance.platform.data.etl.clean;

import com.finance.platform.data.service.DataEtlService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

@SpringBootTest
public class cleanDataTest {
    @Autowired
    DataEtlService dataEtlService;

    @Test
    public void testCleanData() {
        dataEtlService.cleanData("7cd9ebcfeb4d402894be33942671d05f");
    }
}
