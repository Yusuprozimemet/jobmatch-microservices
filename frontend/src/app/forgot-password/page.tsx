"use client";

import Link from "next/link";
import { type FormEvent, useState } from "react";
import { forgotPassword } from "@/lib/api";

export default function ForgotPasswordPage() {
  const [email, setEmail] = useState("");
  const [isSubmitting, setIsSubmitting] = useState(false);
  const [isSuccess, setIsSuccess] = useState(false);
  const [error, setError] = useState("");

  async function handleSubmit(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();

    setError("");
    setIsSubmitting(true);

    try {
      await forgotPassword({
        email: email.trim(),
      });

      setIsSuccess(true);
    } catch {
      setError(
        "We could not process your request right now. Please try again.",
      );
    } finally {
      setIsSubmitting(false);
    }
  }

  return (
    <section className="auth-page">
      <div className="auth-intro">
        <p className="auth-eyebrow">PASSWORD RECOVERY</p>

        <h1 className="auth-display-title">
          Get back
          <br />
          to your
          <br />
          search.
        </h1>

        <p className="auth-intro-copy">
          Enter the email address connected to your JobMatch account and we’ll
          send you instructions to reset your password.
        </p>

        <div className="auth-signal-list">
          <div>
            <span>01</span>
            <p>Enter the email address connected to your account.</p>
          </div>

          <div>
            <span>02</span>
            <p>Open the secure password reset link.</p>
          </div>

          <div>
            <span>03</span>
            <p>Create a new password and continue your job search.</p>
          </div>
        </div>
      </div>

      <div className="auth-form-column">
        <div className="auth-form-header">
          <p className="auth-form-kicker">FORGOT PASSWORD</p>
          <h2>Reset your password.</h2>
          <p>We’ll send reset instructions to your email address.</p>
        </div>

        {isSuccess ? (
          <>
            <output className="auth-message auth-message-success">
              If an account exists for this email address, we’ve sent password
              reset instructions.
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
                <label htmlFor="email">Email address</label>

                <input
                  id="email"
                  name="email"
                  type="email"
                  autoComplete="email"
                  value={email}
                  onChange={(event) => setEmail(event.target.value)}
                  placeholder="you@example.com"
                  required
                />
              </div>

              <button
                className="auth-primary-button"
                type="submit"
                disabled={isSubmitting}
              >
                {isSubmitting ? "Sending..." : "Send reset link"}
              </button>
            </form>

            <p className="auth-switch">
              Remember your password? <Link href="/login">Sign in</Link>
            </p>
          </>
        )}
      </div>
    </section>
  );
}
