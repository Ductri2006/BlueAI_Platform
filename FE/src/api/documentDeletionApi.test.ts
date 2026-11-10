import {describe, expect, it, vi} from 'vitest';
import {BlueAiApi} from './blueAiApi';

describe('document deletion API', () => {
  it('deletes only the selected document with bearer authentication and handles an empty 204 response', async () => {
    const fetchImpl = vi.fn(async () => new Response(null, {status:204}));
    const api = new BlueAiApi({accessToken:'qa-document-access',fetchImpl:fetchImpl as typeof fetch});
    await expect(api.deleteDocument('document/id ?')).resolves.toBeUndefined();
    const [path, options] = fetchImpl.mock.calls[0] as unknown as [string,RequestInit];
    expect(path).toBe('/api/v1/documents/document%2Fid%20%3F');
    expect(options.method).toBe('DELETE');
    expect(new Headers(options.headers).get('Authorization')).toBe('Bearer qa-document-access');
  });

  it.each([403,404,503])('preserves HTTP %i so the UI can handle permissions, concurrent deletion and retries', async status => {
    const fetchImpl = vi.fn(async () => new Response(JSON.stringify({code:'QA_DELETE_ERROR',message:'QA deletion rejected'}), {status}));
    const api = new BlueAiApi({accessToken:'qa-document-access',fetchImpl:fetchImpl as typeof fetch});
    await expect(api.deleteDocument('qa-document')).rejects.toMatchObject({status,code:'QA_DELETE_ERROR'});
    expect(fetchImpl).toHaveBeenCalledOnce();
  });
});
