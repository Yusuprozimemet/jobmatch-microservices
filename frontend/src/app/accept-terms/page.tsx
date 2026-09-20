"use client";

import Link from "next/link";
import { useRouter } from "next/navigation";
import { useState } from "react";

import { ApiError, acceptTerms } from "@/lib/api";

export default function AcceptTermsPage() {
  const router = useRouter();

  const [accepted, setAccepted] = useState(false);
  const [isSubmitting, setIsSubmitting] = useState(false);
  const [error, setError] = useState("");

  async function handleAcceptTerms() {
    if (!accepted) {
      setError("You must accept the Terms and Privacy Policy to continue.");
      return;
    }

    setError("");
    setIsSubmitting(true);

    try {
      await acceptTerms();
      router.replace("/");
      router.refresh();
    } catch (error) {
      if (error instanceof ApiError && error.status === 401) {
        router.replace("/login");
        return;
      }

      setError("We could not save your agreement. Please try again.");
      setIsSubmitting(false);
    }
  }

  return (
    <section className="legal-page">
      <div className="legal-container">
        <p className="legal-eyebrow">LEGAL</p>

        <h1>Before you continue</h1>

        <p className="legal-updated">
          Please review and accept the Terms & Conditions and Privacy Policy.
        </p>

        <div className="legal-content">
          <section>
            <h2>Terms & Privacy</h2>

            <p>
              Your Google account is connected. Before using JobMatch, you need
              to accept our Terms & Conditions and acknowledge our Privacy
              Policy.
            </p>

            <p>
              You can read the{" "}
              <Link href="/terms" target="_blank">
                Terms & Conditions
              </Link>{" "}
              and{" "}
              <Link href="/privacy" target="_blank">
                Privacy Policy
              </Link>
              .
            </p>
          </section>

          <section>
            <label className="auth-terms-label" htmlFor="acceptTerms">
              <input
                id="acceptTerms"
                type="checkbox"
                checked={accepted}
                onChange={(event) => {
                  setAccepted(event.target.checked);

                  if (event.target.checked) {
                    setError("");
                  }
                }}
              />

              <span>
                I agree to the Terms & Conditions and acknowledge the Privacy
                Policy.
              </span>
            </label>

            {error ? (
              <p className="auth-field-error" role="alert">
                {error}
              </p>
            ) : null}

            <button
              className="auth-primary-button"
              type="button"
              onClick={handleAcceptTerms}
              disabled={isSubmitting}
            >
              {isSubmitting ? "Saving..." : "Accept and continue"}
            </button>
          </section>
        </div>
      </div>
    </section>
  );
}
