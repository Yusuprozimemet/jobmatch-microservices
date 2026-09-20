"use client";

import Link from "next/link";
import { useRouter, useSearchParams } from "next/navigation";
import { type FormEvent, Suspense, useState } from "react";
import { useAuth } from "@/context/AuthContext";
import { loginUser } from "@/lib/api";

function LoginContent() {
  const router = useRouter();
  const searchParams = useSearchParams();
  const { refreshUser } = useAuth();

  const [email, setEmail] = useState("");
  const [password, setPassword] = useState("");
  const [formError, setFormError] = useState("");
  const [isSubmitting, setIsSubmitting] = useState(false);
  const [isPasswordVisible, setIsPasswordVisible] = useState(false);

  const oauthError = searchParams.get("error");
  const registrationSuccessful = searchParams.get("registered") === "true";

  async function handleSubmit(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();

    setFormError("");
    setIsSubmitting(true);

    try {
      await loginUser({
        email: email.trim(),
        password,
      });

      await refreshUser();
      router.push("/");
    } catch {
      setFormError("Invalid email or password.");
    } finally {
      setIsSubmitting(false);
    }
  }

  function handleGoogleLogin() {
    window.location.href = "/api/oauth2/authorization/google";
  }

  let oauthMessage = "";

  if (oauthError === "oauth") {
    oauthMessage = "Google sign-in could not be completed. Please try again.";
  }

  if (oauthError === "google_link_required") {
    oauthMessage =
      "An account with this email already exists. Log in with your email and password first.";
  }

  return (
    <section className="auth-page">
      <div className="auth-intro">
        <p className="auth-eyebrow">WELCOME BACK</p>

        <h1 className="auth-display-title">
          Continue
          <br />
          your search
          <br />
          with clarity.
        </h1>

        <p className="auth-intro-copy">
          Your matches, saved jobs, and application progress stay together in
          one place.
        </p>

        <div className="auth-signal-list">
          {" "}
          <div>
            <span>01</span>
            <p>Understand why a job matches you.</p>
          </div>
          <div>
            <span>02</span>
            <p>Save opportunities worth coming back to.</p>
          </div>
          <div>
            <span>03</span>
            <p>Keep track after you apply externally.</p>
          </div>
        </div>
      </div>

      <div className="auth-form-column">
        <div className="auth-form-header">
          <p className="auth-form-kicker">SIGN IN</p>
          <h2>Welcome back.</h2>
          <p>Enter your details to continue to JobMatch.</p>
        </div>

        {registrationSuccessful ? (
          <output className="auth-message auth-message-success">
            Account created successfully. Please log in to continue.
          </output>
        ) : null}

        {oauthMessage ? (
          <div className="auth-message auth-message-error" role="alert">
            {oauthMessage}
          </div>
        ) : null}

        {formError ? (
          <div className="auth-message auth-message-error" role="alert">
            {formError}
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

          <div className="auth-field">
            <label htmlFor="password">Password</label>
            <div className="auth-password-input">
              <input
                id="password"
                name="password"
                type={isPasswordVisible ? "text" : "password"}
                autoComplete="current-password"
                value={password}
                onChange={(event) => setPassword(event.target.value)}
                placeholder="Enter your password"
                required
              />
              <button
                type="button"
                aria-label={
                  isPasswordVisible ? "Hide password" : "Show password"
                }
                onClick={() => setIsPasswordVisible((isVisible) => !isVisible)}
              >
                {isPasswordVisible ? "Hide" : "Show"}
              </button>
            </div>
          </div>

          <div className="auth-password-row">
            <Link href="/forgot-password" className="auth-forgot-link">
              Forgot password?
            </Link>
          </div>

          <button
            className="auth-primary-button"
            type="submit"
            disabled={isSubmitting}
          >
            {isSubmitting ? "Signing in..." : "Sign in"}
          </button>
        </form>

        <div className="auth-divider" aria-hidden="true">
          <span />
          <p>or</p>
          <span />
        </div>

        <button
          className="auth-google-button"
          type="button"
          onClick={handleGoogleLogin}
        >
          <span className="google-mark" aria-hidden="true">
            G
          </span>
          Continue with Google
        </button>

        <p className="auth-switch">
          New to JobMatch? <Link href="/register">Create an account</Link>
        </p>
      </div>
    </section>
  );
}

export default function LoginPage() {
  return (
    <Suspense
      fallback={
        <section className="auth-page">
          <p className="auth-loading">Loading...</p>
        </section>
      }
    >
      <LoginContent />
    </Suspense>
  );
}
