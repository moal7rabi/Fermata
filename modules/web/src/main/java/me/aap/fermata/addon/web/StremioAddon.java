package me.aap.fermata.addon.web;

import android.content.Context;

import androidx.annotation.IdRes;
import androidx.annotation.Keep;
import androidx.annotation.NonNull;

import me.aap.fermata.addon.AddonInfo;
import me.aap.fermata.addon.FermataAddon;
import me.aap.utils.function.BooleanSupplier;
import me.aap.utils.function.IntSupplier;
import me.aap.utils.function.Supplier;
import me.aap.utils.misc.ChangeableCondition;
import me.aap.utils.pref.PreferenceSet;
import me.aap.utils.pref.PreferenceStore;
import me.aap.utils.ui.fragment.ActivityFragment;

/**
 * Stremio Web (web.stremio.com) in its own tab, with its own last page, bookmarks and settings.
 */
@Keep
@SuppressWarnings("unused")
public class StremioAddon extends WebBrowserAddon {
	static final String HOME_URL = "https://web.stremio.com/";
	@NonNull
	private static final AddonInfo info = FermataAddon.findAddonInfo(StremioAddon.class.getName());
	private static final Pref<Supplier<String>> STREMIO_LAST_URL =
			Pref.s("STREMIO_LAST_URL", HOME_URL);
	// Stremio has its own dark theme, forcing dark mode would invert it
	private static final Pref<IntSupplier> STREMIO_DARK_MODE =
			Pref.i("STREMIO_DARK_MODE", DARK_MODE_DISABLED);
	private static final Pref<BooleanSupplier> STREMIO_DESKTOP_VERSION =
			Pref.b("STREMIO_DESKTOP_VERSION", false);
	private static final Pref<Supplier<String[]>> STREMIO_BOOKMARKS = Pref.sa("STREMIO_BOOKMARKS");
	private static final Pref<BooleanSupplier> STREMIO_OPEN_ON_START =
			Pref.b("STREMIO_OPEN_ON_START", false);

	@IdRes
	@Override
	public int getAddonId() {
		return me.aap.fermata.R.id.stremio_fragment;
	}

	@NonNull
	@Override
	public AddonInfo getInfo() {
		return info;
	}

	@NonNull
	@Override
	public ActivityFragment createFragment() {
		return new StremioFragment();
	}

	@Override
	public void contributeSettings(Context ctx, PreferenceStore store, PreferenceSet set,
																 ChangeableCondition visibility) {
		super.contributeSettings(ctx, store, set, visibility);
		set.addBooleanPref(o -> {
			o.store = getPreferenceStore();
			o.pref = getOpenOnStartPref();
			o.title = R.string.open_on_start;
			o.visibility = visibility;
		});
	}

	@Override
	public Pref<IntSupplier> getForceDarkPref() {
		return STREMIO_DARK_MODE;
	}

	@Override
	public Pref<BooleanSupplier> getOpenOnStartPref() {
		return STREMIO_OPEN_ON_START;
	}

	@Override
	public Pref<Supplier<String>> getLastUrlPref() {
		return STREMIO_LAST_URL;
	}

	@Override
	public Pref<BooleanSupplier> getDesktopVersionPref() {
		return STREMIO_DESKTOP_VERSION;
	}

	@Override
	public Pref<Supplier<String[]>> getBookmarksPref() {
		return STREMIO_BOOKMARKS;
	}
}
