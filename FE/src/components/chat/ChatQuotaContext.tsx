import React from 'react';
import { useChatQuota } from './useChatQuota';

export const ChatQuotaContext = React.createContext<ReturnType<typeof useChatQuota> | null>(null);

export function ChatQuotaProvider({ children }: { children: React.ReactNode }) {
  const quota = useChatQuota();
  return <ChatQuotaContext.Provider value={quota}>{children}</ChatQuotaContext.Provider>;
}

export function useSharedChatQuota() {
  const value = React.useContext(ChatQuotaContext);
  if (!value) throw new Error('Chat quota requires the authenticated workspace provider');
  return value;
}
