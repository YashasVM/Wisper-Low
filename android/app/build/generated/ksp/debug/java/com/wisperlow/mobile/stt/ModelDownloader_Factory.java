package com.wisperlow.mobile.stt;

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
public final class ModelDownloader_Factory implements Factory<ModelDownloader> {
  private final Provider<Context> contextProvider;

  public ModelDownloader_Factory(Provider<Context> contextProvider) {
    this.contextProvider = contextProvider;
  }

  @Override
  public ModelDownloader get() {
    return newInstance(contextProvider.get());
  }

  public static ModelDownloader_Factory create(Provider<Context> contextProvider) {
    return new ModelDownloader_Factory(contextProvider);
  }

  public static ModelDownloader newInstance(Context context) {
    return new ModelDownloader(context);
  }
}
