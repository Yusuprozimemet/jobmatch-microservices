"use client";

import Link from "next/link";
import { useSearchParams } from "next/navigation";
import { type FormEvent, Suspense, useState } from "react";
import { ApiError, resetPassword } from "@/lib/api";

function ResetPasswordContent() {
  const searchParams = useSearchParams();
  const token = searchParams.get("token") ?? "";

  const [newPassword, setNewPassword] = useState("");
  const [confirmPassword, setConfirmPassword] = useState("");
  const [isNewPasswordVisible, setIsNewPasswordVisible] = useState(false);
  const [isConfirmPasswordVisible, setIsConfirmPasswordVisible] =
    useState(false);
  const [isSubmitting, setIsSubmitting] = useState(false);
  const [isSuccess, setIsSuccess] = useState(false);
  const [error, setError] = useState("");

  async function handleSubmit(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();

    setError("");

    if (!token) {
      setError("This password reset link is invalid.");
      return;
    }

    if (newPassword.length < 6) {
      setError("Password must be at least 6 characters.");
      return;
    }

    if (newPassword !== confirmPassword) {
      setError("Passwords do not match.");
      return;
    }

    setIsSubmitting(true);

    try {
      await resetPassword({
        token,
        newPassword,
      });

      setIsSuccess(true);
    } catch (error) {
      if (
        error instanceof ApiError &&
        (error.status === 400 || error.status === 401)
      ) {
        setError(
          "This password reset link is invalid or has expired. Please request a new one.",
        );
      } else {
        setError("We could not reset your password. Please try again.");
      }
    } finally {
      setIsSubmitting(false);
    }
  }

  if (!token) {
    return (
      <section className="auth-page">
        <div className="auth-intro">
          <p className="auth-eyebrow">PASSWORD RECOVERY</p>

          <h1 className="auth-display-title">
            This link
            <br />
            can&apos;t be
            <br />
            used.
          </h1>

          <p className="auth-intro-copy">
            The reset link is missing the information needed to update your
            password.
          </p>
        </div>

        <div className="auth-form-column">
          <div className="auth-form-header">
            <p className="auth-form-kicker">INVALID LINK</p>

            <h2>Request a new reset link.</h2>

            <p>
              Go back to password recovery and we&apos;ll send you a new secure
              link.
            </p>
          </div>

          <div className="auth-message auth-message-error" role="alert">
            This password reset link is invalid.
          </div>

          <p className="auth-switch">
            <Link href="/forgot-password">Request a new reset link</Link>
          </p>
        </div>
      </section>
    );
  }

  return (
    <section className="auth-page">
      <div className="auth-intro">
        <p className="auth-eyebrow">PASSWORD RECOVERY</p>

        <h1 className="auth-display-title">
          Choose
          <br />a new
          <br />
          password.
        </h1>

        <p className="auth-intro-copy">
          Create a new password for your JobMatch account and get back to your
          job search.
        </p>

        <div className="auth-signal-list">
          <div>
            <span>01</span>
            <p>Create a new secure password.</p>
          </div>

          <div>
            <span>02</span>
            <p>Confirm the password before submitting.</p>
          </div>

          <div>
            <span>03</span>
            <p>Return to sign in with your new password.</p>
          </div>
        </div>
      </div>

      <div className="auth-form-column">
        <div className="auth-form-header">
          <p className="auth-form-kicker">RESET PASSWORD</p>

          <h2>Set a new password.</h2>

          <p>Choose a password with at least 6 characters.</p>
        </div>

        {isSuccess ? (
          <>
            <output className="auth-message auth-message-success">
              Your password has been reset successfully. You can now sign in
              with your new password.
            </output>

            <p className="auth-switch">
              <Link href="/login">Back to sign in</Link>
            </p>
          </>
        ) : (
          <>
            {error ? (
              <div className="auth-message auth-message-error" role="alert">
                {error}
              </div>
            ) : null}

            <form className="auth-form" onSubmit={handleSubmit}>
              <div className="auth-field">
                <label htmlFor="newPassword">New password</label>

                <div className="auth-password-input">
                  <input
                    id="newPassword"
                    name="newPassword"
                    type={isNewPasswordVisible ? "text" : "password"}
                    autoComplete="new-password"
                    value={newPassword}
                    onChange={(event) => setNewPassword(event.target.value)}
                    placeholder="At least 6 characters"
                    required
                  />
                  <button
                    type="button"
                    aria-label={
                      isNewPasswordVisible
                        ? "Hide new password"
                        : "Show new password"
                    }
                    onClick={() =>
                      setIsNewPasswordVisible((isVisible) => !isVisible)
                    }
                  >
                    {isNewPasswordVisible ? "Hide" : "Show"}
                  </button>
                </div>

                <p className="auth-field-hint">Use at least 6 characters.</p>
              </div>

              <div className="auth-field">
                <label htmlFor="confirmPassword">Confirm new password</label>

                <div className="auth-password-input">
                  <input
                    id="confirmPassword"
                    name="confirmPassword"
                    type={isConfirmPasswordVisible ? "text" : "password"}
                    autoComplete="new-password"
                    value={confirmPassword}
                    onChange={(event) => setConfirmPassword(event.target.value)}
                    placeholder="Repeat your new password"
                    required
                  />
                  <button
                    type="button"
                    aria-label={
                      isConfirmPasswordVisible
                        ? "Hide confirm password"
                        : "Show confirm password"
                    }
                    onClick={() =>
                      setIsConfirmPasswordVisible((isVisible) => !isVisible)
                    }
                  >
                    {isConfirmPasswordVisible ? "Hide" : "Show"}
                  </button>
                </div>
              </div>

              <button
                className="auth-primary-button"
                type="submit"
                disabled={isSubmitting}
              >
                {isSubmitting ? "Resetting password..." : "Reset password"}
              </button>
            </form>

            <p className="auth-switch">
              Need another link?{" "}
              <Link href="/forgot-password">Request a new one</Link>
            </p>
          </>
        )}
      </div>
    </section>
  );
}

export default function ResetPasswordPage() {
  return (
    <Suspense
      fallback={
        <section className="auth-page">
          <p className="auth-loading">Loading...</p>
        </section>
      }
    >
      <ResetPasswordContent />
    </Suspense>
  );
}
