# 像素鸟 (FlappyBird)

一个用 Java 编写、基于 Android 自定义 View + Canvas 渲染的 Flappy Bird 复刻小游戏，不依赖任何游戏引擎。

## 玩法

点击屏幕让小鸟向上拍翅，松开后受重力下落。穿过管道之间的空隙即可得分，撞到管道或落到地面则游戏结束。

- **READY** 待机界面：点击「设置」调整参数，或点击屏幕开始游戏
- **PLAYING** 游戏进行中：点击屏幕拍翅
- **GAME_OVER** 结算界面：显示本局得分与最高分，稍作停顿后点击屏幕重新开始

## 功能特性

- 完整的状态机游戏循环（待机 / 游戏中 / 结算 / 设置面板）
- **可调参数**：管道移动速度、重力、拍翅力度，三档滑杆实时调节并持久化
- **最高分**：本地记录并展示
- 界面全中文，适配竖屏沉浸式全屏显示
- 自定义像素鸟风格应用图标（自适应图标 + 低版本位图回退）

## 技术要点

- 世界模型：小鸟固定在屏幕横向位置，仅做竖直运动，管道与地面纹理整体向左滚动，按世界坐标生成与回收管道
- 渲染循环：`postOnAnimation` 驱动，按帧间隔推进物理与动画
- 持久化：`SharedPreferences` 保存最高分与三项设置值（无数据库依赖）
- 数据存放于应用私有目录的 `shared_prefs/flappy_bird.xml`

## 项目结构

```
app/src/main/java/com/example/flappybird/
├── MainActivity.java   全屏沉浸式入口，承载 GameView
└── GameView.java       全部游戏逻辑、渲染、设置面板与本地化
```

## 环境要求

- Android Studio（含 JDK）
- AGP 9.3.0，Gradle 9.5，compileSdk 37，minSdk 24，Java 11
- 支持 Android 7.0（API 24）及以上设备

## 构建

在项目根目录执行：

```bash
./gradlew :app:assembleDebug
```

Windows 下如直接运行 `gradlew.bat` 报错，可用 Android Studio 自带 JDK 运行 wrapper：

```powershell
$env:JAVA_HOME = "C:\Program Files\Android\Android Studio\jbr"
& "$env:JAVA_HOME\bin\java.exe" -jar gradle\wrapper\gradle-wrapper.jar :app:assembleDebug
```

生成 APK 位于 `app/build/outputs/apk/debug/`。
