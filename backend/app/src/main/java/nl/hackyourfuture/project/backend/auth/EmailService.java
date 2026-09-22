package nl.hackyourfuture.project.backend.auth;

import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.scheduling.annotation.Async;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.stereotype.Service;

@Slf4j
@EnableAsync
@Service
@RequiredArgsConstructor
public class EmailService {

    private final JavaMailSender mailSender;
    @Value("${app.mail.from:no-reply@hackyourfuture.nl}")
    private String mailFrom;

    @Value("${spring.mail.username:none}")
    private String mailUsername;

    @PostConstruct
    public void checkMailConfiguration() {
        if (mailUsername == null || mailUsername.isBlank() || "none".equals(mailUsername) ||  mailUsername.contains("${")) {
            log.warn("⚠️ Mail service warning: MAIL_USERNAME / MAIL_PASSWORD are not configured." +
                    " Password reset emails will fail to send!");
        } else {
            log.info("Email service configured successfully with user: {}", mailUsername);
        }
    }
    @Async
    public void sendPasswordResetEmail(String toEmail, String resetUrl) {
        try {
            SimpleMailMessage message = new SimpleMailMessage();
            message.setFrom(mailFrom);
            message.setTo(toEmail);
            message.setSubject("Password Reset Request - JobMatch");
            message.setText("To reset your password, click the link below:\n" + resetUrl);

            mailSender.send(message);
            log.info("Password reset email successfully sent asynchronously to: {}", toEmail);
        } catch (Exception e) {
            log.error("Failed to send password reset email to {}: {}", toEmail, e.getMessage());
        }
    }
}