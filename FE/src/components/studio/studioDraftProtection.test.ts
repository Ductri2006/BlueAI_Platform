import { afterEach, describe, expect, it, vi } from 'vitest';
import { beginStudioOperation, confirmStudioExit, installStudioExitProtection, isStudioExitControl } from './studioDraftProtection';

describe('studio draft protection', () => {
  it('keeps a dirty draft when bot switch/create/leave is cancelled', async () => {
    const guard = { dirty: true, busy: false };
    const confirm = vi.fn(() => false);
    expect(await confirmStudioExit(guard, confirm, vi.fn())).toBe(false);
    expect(confirm).toHaveBeenCalledOnce();
    expect(guard.dirty).toBe(true);
    expect(await confirmStudioExit(guard, () => true, vi.fn())).toBe(true);
    // Confirmation itself never discards data; only a successfully loaded bot clears it.
    expect(guard.dirty).toBe(true);
  });

  it('blocks rapid repeated saves and navigation throughout an in-flight operation', async () => {
    const guard = { dirty: true, busy: false };
    expect(beginStudioOperation(guard)).toBe(true);
    expect(beginStudioOperation(guard)).toBe(false);
    const confirm = vi.fn(() => true);
    const onBusy = vi.fn();
    expect(await confirmStudioExit(guard, confirm, onBusy)).toBe(false);
    expect(confirm).not.toHaveBeenCalled();
    expect(onBusy).toHaveBeenCalledOnce();
    expect(guard.dirty).toBe(true);
    guard.busy = false;
    expect(await confirmStudioExit(guard, () => false, onBusy)).toBe(false);
  });

  it('leaves a clean draft without a discard prompt', async () => {
    const confirm = vi.fn();
    expect(await confirmStudioExit({ dirty: false, busy: false }, confirm, vi.fn())).toBe(true);
    expect(confirm).not.toHaveBeenCalled();
  });

  it('rechecks an operation started while the user is deciding', async () => {
    const guard = { dirty: true, busy: false };
    let decide!: (accepted: boolean) => void;
    const onBusy = vi.fn();
    const pending = confirmStudioExit(guard, () => new Promise(resolve => { decide = resolve; }), onBusy);
    guard.busy = true;
    decide(true);
    expect(await pending).toBe(false);
    expect(onBusy).toHaveBeenCalledOnce();
    expect(guard.dirty).toBe(true);
  });
});

class Control {
  isConnected = true;
  click = vi.fn();
  constructor(private attributes: Record<string, string>, private inSidebar = false) {}
  getAttribute(name: string) { return this.attributes[name] ?? null; }
  hasAttribute(name: string) { return name in this.attributes; }
  matches(selector: string) { return selector === 'a[href]' && this.hasAttribute('href'); }
  closest(selector: string): Control | null { return selector.startsWith('aside') ? this.inSidebar ? this : null : this; }
}

describe('studio exit listeners', () => {
  afterEach(() => vi.unstubAllGlobals());
  it('covers app workspace/logout links but permits theme, sidebar collapse and separate tabs', () => {
    expect(isStudioExitControl(new Control({ title: 'Hỏi BlueAI' }, true) as unknown as Element)).toBe(true);
    expect(isStudioExitControl(new Control({ 'aria-label': 'Đăng xuất' }) as unknown as Element)).toBe(true);
    expect(isStudioExitControl(new Control({ title: 'Thu gọn menu' }, true) as unknown as Element)).toBe(false);
    expect(isStudioExitControl(new Control({ 'aria-label': 'Chuyển sang chế độ sáng' }) as unknown as Element)).toBe(false);
    expect(isStudioExitControl(new Control({ href: '/other', target: '_blank' }) as unknown as Element)).toBe(false);
  });

  it('prevents reload/close and intercepted navigation while dirty, and removes listeners on unmount', () => {
    const browser = Object.assign(new EventTarget(), { confirm: vi.fn(() => false) });
    const documentEvents = new EventTarget();
    vi.stubGlobal('window', browser);
    vi.stubGlobal('document', documentEvents);
    vi.stubGlobal('Element', Control);
    const guard = { dirty: true, busy: false };
    const confirm = vi.fn(async () => false);
    const cleanup = installStudioExitProtection(() => ({ contains: () => false }) as unknown as HTMLElement, guard, vi.fn(), confirm);
    const unload = new Event('beforeunload', { cancelable: true });
    browser.dispatchEvent(unload);
    expect(unload.defaultPrevented).toBe(true);
    const click = new Event('click', { cancelable: true });
    Object.defineProperty(click, 'target', { value: new Control({ title: 'Hỏi BlueAI' }, true) });
    documentEvents.dispatchEvent(click);
    expect(click.defaultPrevented).toBe(true);
    expect(confirm).toHaveBeenCalledOnce();
    expect(browser.confirm).not.toHaveBeenCalled();
    cleanup();
    const after = new Event('beforeunload', { cancelable: true });
    browser.dispatchEvent(after);
    expect(after.defaultPrevented).toBe(false);
  });

  const navigationFixture = (contains = false) => {
    const browser = new EventTarget();
    const documentEvents = new EventTarget();
    vi.stubGlobal('window', browser);
    vi.stubGlobal('document', documentEvents);
    vi.stubGlobal('Element', Control);
    const guard = { dirty: true, busy: false };
    const control = new Control({ title: 'Hỏi BlueAI' }, true);
    let decide!: (accepted: boolean) => void;
    const confirm = vi.fn(() => new Promise<boolean>(resolve => { decide = resolve; }));
    const onBusy = vi.fn();
    const cleanup = installStudioExitProtection(() => ({ contains: () => contains }) as unknown as HTMLElement,
      guard, onBusy, confirm);
    const dispatch = (attributes: Record<string, unknown> = {}) => {
      const event = new Event('click', { cancelable: true });
      Object.defineProperty(event, 'target', { value: control });
      for (const [key, value] of Object.entries(attributes)) Object.defineProperty(event, key, { value });
      documentEvents.dispatchEvent(event);
      return event;
    };
    const resolve = async (accepted: boolean) => {
      decide(accepted);
      // Await both the guard decision and the listener's navigation continuation.
      await Promise.resolve();
      await Promise.resolve();
    };
    return { browser, guard, control, confirm, onBusy, cleanup, dispatch, resolve };
  };

  it('waits for acceptance, then replays navigation exactly once without asking again', async () => {
    const fixture = navigationFixture();
    fixture.control.click.mockImplementation(() => fixture.dispatch());
    expect(fixture.dispatch().defaultPrevented).toBe(true);
    expect(fixture.control.click).not.toHaveBeenCalled();
    await fixture.resolve(true);
    expect(fixture.control.click).toHaveBeenCalledOnce();
    expect(fixture.confirm).toHaveBeenCalledOnce();
    fixture.cleanup();
  });

  it('never replays navigation on Cancel', async () => {
    const fixture = navigationFixture();
    fixture.dispatch();
    await fixture.resolve(false);
    expect(fixture.control.click).not.toHaveBeenCalled();
    expect(fixture.guard.dirty).toBe(true);
    fixture.cleanup();
  });

  it.each(['unmounted', 'detached', 'busy'])('blocks delayed acceptance when the target is %s', async reason => {
    const fixture = navigationFixture();
    fixture.dispatch();
    if (reason === 'unmounted') fixture.cleanup();
    if (reason === 'detached') fixture.control.isConnected = false;
    if (reason === 'busy') fixture.guard.busy = true;
    await fixture.resolve(true);
    expect(fixture.control.click).not.toHaveBeenCalled();
    if (reason === 'busy') expect(fixture.onBusy).toHaveBeenCalledOnce();
    fixture.cleanup();
  });

  it('does not intercept editing controls inside Studio or navigation from a clean draft', () => {
    const inside = navigationFixture(true);
    expect(inside.dispatch().defaultPrevented).toBe(false);
    expect(inside.confirm).not.toHaveBeenCalled();
    inside.cleanup();
    const clean = navigationFixture();
    clean.guard.dirty = false;
    expect(clean.dispatch().defaultPrevented).toBe(false);
    expect(clean.confirm).not.toHaveBeenCalled();
    const unload = new Event('beforeunload', { cancelable: true });
    clean.browser.dispatchEvent(unload);
    expect(unload.defaultPrevented).toBe(false);
    clean.cleanup();
  });

  it.each([{ ctrlKey: true }, { metaKey: true }, { shiftKey: true }, { altKey: true }, { button: 1 }])(
    'preserves modified or non-primary clicks: %j', attributes => {
      const fixture = navigationFixture();
      expect(fixture.dispatch(attributes).defaultPrevented).toBe(false);
      expect(fixture.confirm).not.toHaveBeenCalled();
      fixture.cleanup();
    });
});
