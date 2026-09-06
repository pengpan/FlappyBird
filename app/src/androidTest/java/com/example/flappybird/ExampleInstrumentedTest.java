package com.example.flappybird;

import android.content.Context;

import androidx.test.platform.app.InstrumentationRegistry;
import androidx.test.ext.junit.runners.AndroidJUnit4;

import org.junit.Test;
import org.junit.runner.RunWith;

import static org.junit.Assert.*;

/**
 * 仪器化测试示例，需要在 Android 真机 / 模拟器上运行。
 *
 * @see <a href="http://d.android.com/tools/testing">测试相关文档</a>
 */
@RunWith(AndroidJUnit4.class)
public class ExampleInstrumentedTest {
    // 验证能取到被测应用的上下文，且包名正确
    @Test
    public void useAppContext() {
        // 被测应用（app under test）的上下文
        Context appContext = InstrumentationRegistry.getInstrumentation().getTargetContext();
        assertEquals("com.example.flappybird", appContext.getPackageName());
    }
}
