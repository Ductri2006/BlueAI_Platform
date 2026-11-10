import React from 'react';
import {renderToStaticMarkup} from 'react-dom/server';
import {describe, expect, it, vi} from 'vitest';
import {useAuth} from '../../auth/AuthContext';
import {PasswordChangeForm, PasswordChangePage} from './PasswordChangeForm';

vi.mock('../../auth/AuthContext', () => ({useAuth: vi.fn()}));

describe('password recovery screen', () => {
  it('shows only new password inputs during a restricted recovery session', () => {
    vi.mocked(useAuth).mockReturnValue({user: {passwordChangeRequired:true, email:'qa@example.test'}, logout:vi.fn()} as unknown as ReturnType<typeof useAuth>);
    const html = renderToStaticMarkup(<PasswordChangePage />);
    expect(html).toContain('Đặt mật khẩu mới');
    expect(html).toContain('phiên khôi phục');
    expect(html).not.toContain('Mật khẩu hiện tại');
    expect(html).not.toContain('Quản trị Hệ thống');
    expect(html.match(/type="password"/g)).toHaveLength(2);
  });
  it('requires the current password during an ordinary password change', () => {
    vi.mocked(useAuth).mockReturnValue({user: {passwordChangeRequired:false}, logout:vi.fn()} as unknown as ReturnType<typeof useAuth>);
    const html = renderToStaticMarkup(<PasswordChangeForm />);
    expect(html).toContain('Mật khẩu hiện tại');
    expect(html.match(/type="password"/g)).toHaveLength(3);
  });
});
