"use client";

import Link from "next/link";
import { useRouter } from "next/navigation";
import { type FormEvent, useState } from "react";

import { ApiError, registerUser } from "@/lib/api";

type FormErrors = {
  firstName?: string;
  lastName?: string;
  email?: string;
  password?: string;
  acceptedTerms?: string;
  form?: string;
};

export default function RegisterPage() {
  const router = useRouter();

  const [firstName, setFirstName] = useState("");
  const [lastName, setLastName] = useState("");
  const [email, setEmail] = useState("");
  const [password, setPassword] = useState("");
  const [acceptedTerms, setAcceptedTerms] = useState(false);

  const [errors, setErrors] = useState<FormErrors>({});
  const [isSubmitting, setIsSubmitting] = useState(false);
  const [isPasswordVisible, setIsPasswordVisible] = useState(false);

  function validateForm() {
    const nextErrors: FormErrors = {};

    if (!firstName.trim()) {
      nextErrors.firstName = "First name is required.";
    }

    if (!lastName.trim()) {
      nextErrors.lastName = "Last name is required.";
    }

    if (!email.trim()) {
      nextErrors.email = "Email address is required.";
    }

    if (password.length < 6) {
      nextErrors.password = "Password must be at least 6 characters.";
    }

    if (!acceptedTerms) {
      nextErrors.acceptedTerms =
        "You must accept the Terms and Privacy Policy to create an account.";
    }

    setErrors(nextErrors);

    return Object.keys(nextErrors).length === 0;
  }

  async function handleSubmit(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();

    if (!validateForm()) {
      return;
    }

    setErrors({});
    setIsSubmitting(true);

    try {
      await registerUser({
        name: `${firstName.trim()} ${lastName.trim()}`,
        email: email.trim(),
        password,
        acceptedTerms,
      });

      router.push("/login?registered=true");
    } catch (error) {
      if (error instanceof ApiError && error.status === 409) {
        setErrors({
          form:
            error.detail ??
            "An account with this email address already exists. Try logging in instead.",
        });
      } else {
        setErrors({
          form: "We could not create your account. Please try again.",
        });
      }
    } finally {
      setIsSubmitting(false);
    }
  }

  function handleGoogleRegister() {
    window.location.href = "/api/oauth2/authorization/google";
  }

  return (
    <section className="auth-page">
      <div className="auth-intro">
        <p className="auth-eyebrow">CREATE YOUR PROFILE</p>

        <h1 className="auth-display-title">
          Find work
          <br />
          that actually
          <br />
          fits you.
        </h1>

        <p className="auth-intro-copy">
          Build your JobMatch profile once, then use it to understand which
          opportunities deserve your time.
        </p>

        <div className="auth-signal-list">
          <div>
            <span>01</span>
            <p>See jobs that are more relevant to your skills.</p>
          </div>

          <div>
            <span>02</span>
            <p>Understand your match instead of guessing.</p>
          </div>

          <div>
            <span>03</span>
            <p>Save, apply, and keep your job search organized.</p>
          </div>
        </div>
      </div>

      <div className="auth-form-column">
        <div className="auth-form-header">
          <p className="auth-form-kicker">CREATE ACCOUNT</p>

          <h2>Start with JobMatch.</h2>

          <p>Create your account to unlock personalized match information.</p>
        </div>

        {errors.form ? (
          <div className="auth-message auth-message-error" role="alert">
            {errors.form}
          </div>
        ) : null}

        <form className="auth-form" onSubmit={handleSubmit}>
          <div className="auth-name-grid">
            <div className="auth-field">
              <label htmlFor="firstName">First name</label>

              <input
                id="firstName"
                name="firstName"
                type="text"
                autoComplete="given-name"
                value={firstName}
                onChange={(event) => setFirstName(event.target.value)}
                placeholder="First name"
              />

              {errors.firstName ? (
                <p className="auth-field-error">{errors.firstName}</p>
              ) : null}
            </div>

            <div className="auth-field">
              <label htmlFor="lastName">Last name</label>

              <input
                id="lastName"
                name="lastName"
                type="text"
                autoComplete="family-name"
                value={lastName}
                onChange={(event) => setLastName(event.target.value)}
                placeholder="Last name"
              />

              {errors.lastName ? (
                <p className="auth-field-error">{errors.lastName}</p>
              ) : null}
            </div>
          </div>

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
            />

            {errors.email ? (
              <p className="auth-field-error">{errors.email}</p>
            ) : null}
          </div>

          <div className="auth-field">
            <label htmlFor="password">Password</label>

            <div className="auth-password-input">
              <input
                id="password"
                name="password"
                type={isPasswordVisible ? "text" : "password"}
                autoComplete="new-password"
                value={password}
                onChange={(event) => setPassword(event.target.value)}
                placeholder="At least 6 characters"
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

            {errors.password ? (
              <p className="auth-field-error">{errors.password}</p>
            ) : (
              <p className="auth-field-hint">Use at least 6 characters.</p>
            )}
          </div>

          <div className="auth-terms">
            <label htmlFor="acceptedTerms" className="auth-terms-label">
              <input
                id="acceptedTerms"
                name="acceptedTerms"
                type="checkbox"
                checked={acceptedTerms}
                onChange={(event) => {
                  setAcceptedTerms(event.target.checked);

                  if (event.target.checked) {
                    setErrors((currentErrors) => ({
                      ...currentErrors,
                      acceptedTerms: undefined,
                    }));
                  }
                }}
              />

              <span>
                I agree to the{" "}
                <Link href="/terms" target="_blank">
                  Terms & Conditions
                </Link>{" "}
                and acknowledge the{" "}
                <Link href="/privacy" target="_blank">
                  Privacy Policy
                </Link>
                .
              </span>
            </label>

            {errors.acceptedTerms ? (
              <p className="auth-field-error" role="alert">
                {errors.acceptedTerms}
              </p>
            ) : null}
          </div>

          <button
            className="auth-primary-button"
            type="submit"
            disabled={isSubmitting}
          >
            {isSubmitting ? "Creating account..." : "Create account"}
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
          onClick={handleGoogleRegister}
        >
          <span className="google-mark" aria-hidden="true">
            G
          </span>
          Continue with Google
        </button>

        <p className="auth-switch">
          Already have an account? <Link href="/login">Sign in</Link>
        </p>
      </div>
    </section>
  );
}
