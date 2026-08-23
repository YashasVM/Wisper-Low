package com.wisperlow.mobile.service;

import com.wisperlow.mobile.settings.SettingsRepository;
import com.wisperlow.mobile.stt.ModelDownloader;
import dagger.MembersInjector;
import dagger.internal.DaggerGenerated;
import dagger.internal.InjectedFieldSignature;
import dagger.internal.QualifierMetadata;
import javax.annotation.processing.Generated;
import javax.inject.Provider;

@QualifierMetadata
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
public final class DictationService_MembersInjector implements MembersInjector<DictationService> {
  private final Provider<ModelDownloader> modelDownloaderProvider;

  private final Provider<SettingsRepository> settingsRepositoryProvider;

  public DictationService_MembersInjector(Provider<ModelDownloader> modelDownloaderProvider,
      Provider<SettingsRepository> settingsRepositoryProvider) {
    this.modelDownloaderProvider = modelDownloaderProvider;
    this.settingsRepositoryProvider = settingsRepositoryProvider;
  }

  public static MembersInjector<DictationService> create(
      Provider<ModelDownloader> modelDownloaderProvider,
      Provider<SettingsRepository> settingsRepositoryProvider) {
    return new DictationService_MembersInjector(modelDownloaderProvider, settingsRepositoryProvider);
  }

  @Override
  public void injectMembers(DictationService instance) {
    injectModelDownloader(instance, modelDownloaderProvider.get());
    injectSettingsRepository(instance, settingsRepositoryProvider.get());
  }

  @InjectedFieldSignature("com.wisperlow.mobile.service.DictationService.modelDownloader")
  public static void injectModelDownloader(DictationService instance,
      ModelDownloader modelDownloader) {
    instance.modelDownloader = modelDownloader;
  }

  @InjectedFieldSignature("com.wisperlow.mobile.service.DictationService.settingsRepository")
  public static void injectSettingsRepository(DictationService instance,
      SettingsRepository settingsRepository) {
    instance.settingsRepository = settingsRepository;
  }
}
