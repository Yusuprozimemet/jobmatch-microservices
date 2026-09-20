"use client";

import {
  createContext,
  type ReactNode,
  useCallback,
  useContext,
  useEffect,
  useState,
} from "react";

import {
  type CurrentUserResponse,
  getCurrentUser,
  logoutUser,
} from "@/lib/api";

type AuthContextValue = {
  user: CurrentUserResponse | null;
  isLoading: boolean;
  authError: string | null;
  refreshUser: () => Promise<void>;
  clearUser: () => void;
  logout: () => Promise<void>;
};

const AuthContext = createContext<AuthContextValue | undefined>(undefined);

export function AuthProvider({ children }: { children: ReactNode }) {
  const [user, setUser] = useState<CurrentUserResponse | null>(null);
  const [isLoading, setIsLoading] = useState(true);
  const [authError, setAuthError] = useState<string | null>(null);

  const refreshUser = useCallback(async () => {
    setAuthError(null);

    try {
      const currentUser = await getCurrentUser();
      setUser(currentUser);
    } catch {
      setAuthError("Unable to check your session.");
    }
  }, []);

  const clearUser = useCallback(() => {
    setUser(null);
    setAuthError(null);
  }, []);

  const logout = useCallback(async () => {
    await logoutUser();
    clearUser();
  }, [clearUser]);

  useEffect(() => {
    async function loadUser() {
      await refreshUser();
      setIsLoading(false);
    }

    void loadUser();
  }, [refreshUser]);

  return (
    <AuthContext.Provider
      value={{
        user,
        isLoading,
        authError,
        refreshUser,
        clearUser,
        logout,
      }}
    >
      {children}
    </AuthContext.Provider>
  );
}

export function useAuth() {
  const context = useContext(AuthContext);

  if (!context) {
    throw new Error("useAuth must be used within an AuthProvider");
  }

  return context;
}
