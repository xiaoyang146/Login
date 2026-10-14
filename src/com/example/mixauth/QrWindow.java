package com.example.mixauth;

import java.awt.BorderLayout;
import java.awt.Font;
import java.awt.Graphics2D;
import java.awt.Image;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;

import javax.imageio.ImageIO;
import javax.swing.BorderFactory;
import javax.swing.ImageIcon;
import javax.swing.JFrame;
import javax.swing.JLabel;
import javax.swing.SwingConstants;
import javax.swing.SwingUtilities;

/**
 * Swing 二维码展示窗口：中间显示二维码，下方显示实时登录状态。
 * 仅在有图形环境时使用；无图形环境（--no-gui）时跳过。
 */
public final class QrWindow {

    private final JFrame frame;
    private final JLabel qrLabel;
    private final JLabel statusLabel;
    private volatile boolean closed = false;

    public QrWindow(String type) {
        frame = new JFrame("MixAuth 登录测试 - " + ("qq".equals(type) ? "QQ 登录" : "微信登录"));
        frame.setDefaultCloseOperation(JFrame.DISPOSE_ON_CLOSE);
        frame.setLayout(new BorderLayout());

        qrLabel = new JLabel("", SwingConstants.CENTER);
        qrLabel.setBorder(BorderFactory.createEmptyBorder(16, 24, 8, 24));
        frame.add(qrLabel, BorderLayout.CENTER);

        statusLabel = new JLabel("正在加载二维码...", SwingConstants.CENTER);
        statusLabel.setFont(new Font(statusLabel.getFont().getName(), Font.PLAIN, 15));
        statusLabel.setBorder(BorderFactory.createEmptyBorder(8, 12, 16, 12));
        frame.add(statusLabel, BorderLayout.SOUTH);

        frame.addWindowListener(new java.awt.event.WindowAdapter() {
            @Override
            public void windowClosed(java.awt.event.WindowEvent e) {
                closed = true;
            }
        });
    }

    /** 展示二维码并显示窗口。 */
    public void showQr(byte[] png) {
        try {
            BufferedImage img = ImageIO.read(new ByteArrayInputStream(png));
            if (img == null) {
                throw new RuntimeException("无法解析二维码图片");
            }
            int size = 320;
            BufferedImage scaled = new BufferedImage(size, size, BufferedImage.TYPE_INT_RGB);
            Graphics2D g = scaled.createGraphics();
            g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
            g.drawImage(img, 0, 0, size, size, null);
            g.dispose();
            SwingUtilities.invokeLater(() -> {
                qrLabel.setIcon(new ImageIcon(scaled));
                frame.pack();
                frame.setLocationRelativeTo(null);
                frame.setVisible(true);
            });
        } catch (Exception ex) {
            SwingUtilities.invokeLater(() -> {
                statusLabel.setText("二维码显示失败: " + ex.getMessage());
                frame.pack();
                frame.setLocationRelativeTo(null);
                frame.setVisible(true);
            });
        }
    }

    /** 更新底部状态文字。 */
    public void setStatus(String text) {
        SwingUtilities.invokeLater(() -> {
            statusLabel.setText(text);
            frame.pack();
        });
    }

    /** 关闭窗口。 */
    public void close() {
        SwingUtilities.invokeLater(frame::dispose);
    }

    /** 用户是否已手动关闭窗口（视为放弃登录）。 */
    public boolean isClosed() {
        return closed;
    }
}