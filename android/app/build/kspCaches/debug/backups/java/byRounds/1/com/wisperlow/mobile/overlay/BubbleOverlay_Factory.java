package com.wisperlow.mobile.overlay;

import android.content.Context;
import dagger.internal.DaggerGenerated;
import dagger.internal.Factory;
import dagger.internal.QualifierMetadata;
import dagger.internal.ScopeMetadata;
import javax.annotation.processing.Generated;
import javax.inject.Provider;

@ScopeMetadata("javax.inject.Singleton")
@QualifierMetadata("dagger.hilt.android.qualifiers.ApplicationContext")
@DaggerGenerated
@Generated(
    value = "dagger.internal.codegen.ComponentProcessor",
    comments = "https://dagger.dev"
)
@SuppressWarnings({
    "unchecked",
    "rawtypes",
    "KotlinInternal",
    "KotlinInternalInJava",
    "cast",
    "deprecation"
})
public final class BubbleOverlay_Factory implements Factory<BubbleOverlay> {
  private final Provider<Context> contextProvider;

  public BubbleOverlay_Factory(Provider<Context> contextProvider) {
    this.contextProvider = contextProvider;
  }

  @Override
  public BubbleOverlay get() {
    return newInstance(contextProvider.get());
  }

  public static BubbleOverlay_Factory create(Provider<Context> contextProvider) {
    return new BubbleOverlay_Factory(contextProvider);
  }

  public static BubbleOverlay newInstance(Context context) {
    return new BubbleOverlay(context);
  }
}
