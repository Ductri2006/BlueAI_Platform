import React, { createContext, useCallback, useContext, useEffect, useMemo, useState } from 'react';
import {
  blueAiApi,
  type LoginCredentials,
  type UserResponse,
} from '../api/blueAiApi';

type AuthStatus = 'checking' | 'authenticated' | 'anonymous';

interface AuthContextValue {
  status: AuthStatus;
  user: UserResponse | null;
  login: (credentials: LoginCredentials) => Promise<void>;
  logout: () => Promise<void>;
}

const AuthContext = createContext<AuthContextValue | undefined>(undefined);

export function AuthProvider({ children }: { children: React.ReactNode }) {
  const [status, setStatus] = useState<AuthStatus>('checking');
  const [user, setUser] = useState<UserResponse | null>(null);

  useEffect(() => {
    let active = true;

    const restoreSession = async () => {
      if (!blueAiApi.hasSession()) {
        if (active) setStatus('anonymous');
        return;
      }

      try {
        const currentUser = await blueAiApi.getCurrentUser();
        if (active) {
          setUser(currentUser);
          setStatus('authenticated');
        }
      } catch {
        blueAiApi.clearSession();
        if (active) {
          setUser(null);
          setStatus('anonymous');
        }
      }
    };

    void restoreSession();
    return () => {
      active = false;
    };
  }, []);

  useEffect(() => {
    if (status !== 'authenticated') return;
    return blueAiApi.onSessionCleared(() => {
      setUser(null);
      setStatus('anonymous');
      // Workspace state is module-scoped. Reload so a later login cannot see
      // conversations, documents or drafts cached by the previous account.
      window.location.reload();
    });
  }, [status]);

  useEffect(() => {
    if (status !== 'authenticated') return;
    let checking = false;
    const checkSession = async () => {
      if (checking || document.visibilityState === 'hidden') return;
      checking = true;
      try { await blueAiApi.getCurrentUser(); }
      catch { /* A revoked session is cleared by the API client; network errors keep it. */ }
      finally { checking = false; }
    };
    const interval = window.setInterval(() => { void checkSession(); }, 15000);
    const onFocus = () => { void checkSession(); };
    window.addEventListener('focus', onFocus);
    document.addEventListener('visibilitychange', onFocus);
    return () => {
      window.clearInterval(interval);
      window.removeEventListener('focus', onFocus);
      document.removeEventListener('visibilitychange', onFocus);
    };
  }, [status]);

  const login = useCallback(async (credentials: LoginCredentials) => {
    const currentUser = await blueAiApi.login(credentials);
    setUser(currentUser);
    setStatus('authenticated');
  }, []);

  const logout = useCallback(async () => {
    setStatus('checking');
    try {
      await blueAiApi.logout();
    } finally {
      setUser(null);
      setStatus('anonymous');
      window.location.reload();
    }
  }, []);

  const value = useMemo<AuthContextValue>(() => ({ status, user, login, logout }), [status, user, login, logout]);
  return <AuthContext.Provider value={value}>{children}</AuthContext.Provider>;
}

export function useAuth(): AuthContextValue {
  const context = useContext(AuthContext);
  if (!context) throw new Error('useAuth must be used inside AuthProvider.');
  return context;
}
