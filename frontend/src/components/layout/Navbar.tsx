"use client";

import Image from "next/image";
import Link from "next/link";
import { usePathname, useRouter } from "next/navigation";
import { useEffect, useRef, useState } from "react";
import { useAuth } from "@/context/AuthContext";

export default function Navbar() {
  const router = useRouter();
  const pathname = usePathname();
  const [menuOpen, setMenuOpen] = useState(false);
  const menuToggle = useRef<HTMLButtonElement>(null);
  const { user, isLoading, authError, logout } = useAuth();

  // biome-ignore lint/correctness/useExhaustiveDependencies: Close the menu whenever the pathname changes.
  useEffect(() => {
    setMenuOpen(false);
  }, [pathname]);

  function closeMenu() {
    if (menuOpen) {
      setMenuOpen(false);
      menuToggle.current?.focus();
    }
  }

  useEffect(() => {
    if (!menuOpen) return;

    function handleEscape(event: KeyboardEvent) {
      if (event.key === "Escape") {
        setMenuOpen(false);
        menuToggle.current?.focus();
      }
    }

    document.addEventListener("keydown", handleEscape);
    return () => document.removeEventListener("keydown", handleEscape);
  }, [menuOpen]);

  async function handleLogout() {
    closeMenu();
    await logout();
    router.push("/");
    router.refresh();
  }

  return (
    <header className="site-header">
      <nav
        className="navbar"
        aria-label="Main navigation"
        data-menu-open={menuOpen}
      >
        <Link
          className="brand"
          href="/"
          aria-label="JobMatch home"
          onNavigate={closeMenu}
        >
          <Image
            className="brand-logo"
            src="/jobmatch-logo.svg"
            alt=""
            width={186}
            height={32}
            priority
          />
        </Link>

        <button
          ref={menuToggle}
          className="logout-button nav-menu-toggle"
          type="button"
          aria-label={
            menuOpen ? "Close navigation menu" : "Open navigation menu"
          }
          aria-expanded={menuOpen}
          aria-controls="main-nav-links main-auth-links"
          onClick={() => setMenuOpen(!menuOpen)}
        >
          <svg
            width="24"
            height="24"
            viewBox="0 0 24 24"
            fill="none"
            stroke="currentColor"
            strokeWidth="2"
            aria-hidden="true"
          >
            <path
              d={menuOpen ? "M6 6l12 12M6 18L18 6" : "M3 6h18M3 12h18M3 18h18"}
            />
          </svg>
        </button>

        <div className="nav-links" id="main-nav-links">
          <Link href="/" onNavigate={closeMenu}>
            Home
          </Link>
          <Link href="/jobs" onNavigate={closeMenu}>
            Find Jobs
          </Link>
          <Link href="/saved" onNavigate={closeMenu}>
            Saved
          </Link>
          <Link href="/profile" onNavigate={closeMenu}>
            Profile
          </Link>
        </div>

        <div className="auth-links" id="main-auth-links">
          {isLoading ? null : authError ? (
            <output className="nav-auth-error">Session unavailable</output>
          ) : user ? (
            <>
              <span className="nav-user">{user.name}</span>
              <button
                className="logout-button"
                type="button"
                onClick={handleLogout}
              >
                Log out
              </button>
            </>
          ) : (
            <>
              <Link href="/login" onNavigate={closeMenu}>
                Log in
              </Link>
              <Link
                className="register-link"
                href="/register"
                onNavigate={closeMenu}
              >
                Create account
              </Link>
            </>
          )}
        </div>
      </nav>
    </header>
  );
}
