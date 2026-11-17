package com.infection.metric.app;

import javax.swing.BorderFactory;
import javax.swing.JFrame;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JTextArea;
import javax.swing.SwingUtilities;
import javax.swing.WindowConstants;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Font;
import java.awt.Rectangle;
import java.awt.Robot;
import java.awt.Toolkit;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import javax.imageio.ImageIO;

/**
 * 真实运行截图采集器：从屏幕像素截取真实 Swing 窗口，并把真实命令回显渲染成终端会话图。
 *
 * <p>采集前由 {@code tools/capture_screenshots.sh} 先真跑一次
 * {@code ./run.sh --offline-sample --headless}，回显原样落盘；本类只读取该文本，
 * 不编造任何命令或数字。截完后校验图片非纯色（防止截到黑屏 / 空白窗口）。</p>
 */
public final class ScreenshotCapture {

    private static final Path SHOTS = Paths.get("docs", "screenshots");
    private static final Path SESSION = Paths.get("out", "offline-sample", "terminal-session.txt");
    private static final String COMMAND = "./run.sh --offline-sample --headless";

    private ScreenshotCapture() {}

    public static void main(String[] args) throws Exception {
        Files.createDirectories(SHOTS);
        String sessionText = Files.isRegularFile(SESSION)
                ? Files.readString(SESSION, StandardCharsets.UTF_8)
                : "(未找到真实回显文件，请先执行 tools/capture_screenshots.sh)";

        Robot robot = new Robot();
        MainWindow window = MainWindow.create();
        JFrame frame = window.frame();
        frame.setAlwaysOnTop(true);
        frame.setVisible(true);
        frame.toFront();
        settle(robot);

        Rectangle bounds = frame.getBounds();
        Path main = SHOTS.resolve("desktop-main-window.png");
        shoot(robot, bounds, main);

        // 第二张：切到同组的另一条口径，聚焦中右两栏（影响面清单 + 差异说明）
        window.selectCode("MER-TL-03");
        settle(robot);
        int cut = 250;   // 左侧口径库栏宽度，正好从中栏起始
        int top = 104;   // 跳过窗口标题栏与顶部 header，只截三栏主体
        Rectangle panels = new Rectangle(bounds.x + cut, bounds.y + top,
                bounds.width - cut, bounds.height - top);
        Path panelsPath = SHOTS.resolve("impact-and-diff-panels.png");
        shoot(robot, panels, panelsPath);
        frame.dispose();

        // 第三张：真实终端会话回显
        JFrame terminal = terminalFrame(sessionText);
        terminal.setAlwaysOnTop(true);
        terminal.setVisible(true);
        terminal.toFront();
        settle(robot);
        Path terminalPath = SHOTS.resolve("terminal-recompute-session.png");
        shoot(robot, terminal.getBounds(), terminalPath);
        terminal.dispose();

        System.out.println("截图采集完成：");
        for (Path p : List.of(main, panelsPath, terminalPath)) {
            System.out.println("  " + p + " (" + Files.size(p) + " bytes)");
        }
    }

    private static JFrame terminalFrame(String text) {
        JFrame f = new JFrame("终端 · 院感指标口径影响分析");
        f.setDefaultCloseOperation(WindowConstants.DISPOSE_ON_CLOSE);
        f.setSize(1180, 760);
        f.setLocationRelativeTo(null);

        JPanel root = new JPanel(new BorderLayout());
        root.setBackground(new Color(0x1E1E1E));
        root.setBorder(BorderFactory.createEmptyBorder(16, 16, 16, 16));

        JLabel bar = new JLabel("  ● ● ●    zsh — " + COMMAND);
        bar.setOpaque(true);
        bar.setBackground(new Color(0x323233));
        bar.setForeground(new Color(0xD4D4D4));
        bar.setFont(new Font("Monospaced", Font.PLAIN, 13));
        bar.setBorder(BorderFactory.createEmptyBorder(8, 8, 8, 8));
        root.add(bar, BorderLayout.NORTH);

        String body = "$ " + COMMAND + "\n" + text;
        JTextArea area = new JTextArea(body);
        area.setEditable(false);
        area.setBackground(new Color(0x1E1E1E));
        area.setForeground(new Color(0xD4D4D4));
        area.setCaretColor(new Color(0xD4D4D4));
        area.setFont(new Font("Monospaced", Font.PLAIN, 13));
        area.setBorder(BorderFactory.createEmptyBorder(12, 12, 12, 12));
        root.add(new JScrollPane(area), BorderLayout.CENTER);
        f.setContentPane(root);
        return f;
    }

    private static void settle(Robot robot) throws InterruptedException {
        SwingUtilities.invokeLater(() -> {});
        Thread.sleep(900L);
        Toolkit.getDefaultToolkit().sync();
        robot.delay(250);
    }

    private static void shoot(Robot robot, Rectangle area, Path out) throws IOException {
        BufferedImage image = robot.createScreenCapture(area);
        if (!ImageIO.write(image, "png", out.toFile())) {
            throw new IOException("PNG 编码失败：" + out);
        }
        if (distinctColors(image) < 12) {
            throw new IOException("截图疑似空白 / 黑屏（颜色数过少）：" + out);
        }
    }

    /** 抽样统计颜色种类，防止把纯色未渲染窗口当成有效截图。 */
    private static int distinctColors(BufferedImage image) {
        Set<Integer> colors = new HashSet<>();
        int w = image.getWidth();
        int h = image.getHeight();
        for (int y = 0; y < h; y += Math.max(1, h / 60)) {
            for (int x = 0; x < w; x += Math.max(1, w / 60)) {
                colors.add(image.getRGB(x, y));
                if (colors.size() >= 64) return colors.size();
            }
        }
        return colors.size();
    }
}
