// @vitest-environment jsdom
import React, { act } from 'react';
import { createRoot } from 'react-dom/client';
import { afterEach, expect, it, vi } from 'vitest';
import { blueAiApi } from '../../api/blueAiApi';
import { ChatQuotaProvider, useSharedChatQuota } from './ChatQuotaContext';

afterEach(() => { vi.restoreAllMocks(); document.body.replaceChildren(); });
it('shares one subscription and one snapshot between header, composer and preview', async () => {
  Object.assign(globalThis, { IS_REACT_ACT_ENVIRONMENT:true });
  let notify: (() => void) | undefined;
  const unsubscribe=vi.fn();
  const getAi=vi.spyOn(blueAiApi,'getAiTokenQuota').mockResolvedValue({enabled:true,serverTime:'',weekly:null,fiveHour:{used:2200,limit:100000,remaining:97800,reserved:0,uncertain:0,resetsAt:null,resetAfterSeconds:0}});
  const getRate=vi.spyOn(blueAiApi,'getChatQuota').mockResolvedValue({limit:30,remaining:30,windowSeconds:60,resetsAt:null});
  const subscribe=vi.spyOn(blueAiApi,'onQuotaChanged').mockImplementation(callback=>{notify=callback;return unsubscribe;});
  function Consumer(){const quota=useSharedChatQuota();return <span>{quota.aiQuota?.fiveHour?.used}</span>;}
  const host=document.createElement('div');document.body.append(host);const root=createRoot(host);
  try {
    await act(async()=>root.render(<ChatQuotaProvider><Consumer/><Consumer/><Consumer/></ChatQuotaProvider>));
    expect(getAi).toHaveBeenCalledTimes(1);expect(getRate).toHaveBeenCalledTimes(1);expect(subscribe).toHaveBeenCalledTimes(1);
    expect(host.textContent).toBe('220022002200');
    getAi.mockResolvedValue({enabled:true,serverTime:'',weekly:null,fiveHour:{used:4474,limit:100000,remaining:95526,reserved:0,uncertain:0,resetsAt:null,resetAfterSeconds:0}});
    await act(async()=>notify?.());
    expect(host.textContent).toBe('447444744474');expect(getAi).toHaveBeenCalledTimes(2);
  } finally {await act(async()=>root.unmount());}
  expect(unsubscribe).toHaveBeenCalledTimes(1);
});
