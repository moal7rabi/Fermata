package me.aap.fermata.addon.web;

import androidx.annotation.Keep;
import androidx.annotation.Nullable;

import me.aap.fermata.addon.AddonManager;

/**
 * The browser, opening Stremio Web. Links to other sites open in the same tab, YouTube links (e.g.
 * trailers) open in the YouTube tab, as in the web browser.
 */
@Keep
@SuppressWarnings("unused")
public class StremioFragment extends WebBrowserFragment {

	@Override
	public int getFragmentId() {
		return me.aap.fermata.R.id.stremio_fragment;
	}

	@Nullable
	@Override
	protected WebBrowserAddon getAddon() {
		return AddonManager.get().getAddon(StremioAddon.class);
	}

	@Override
	protected String getSearchUrl() {
		return StremioAddon.HOME_URL + "#/search?search=";
	}
}
