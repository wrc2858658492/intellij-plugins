package com.intellij.lang.javascript.flex.projectStructure.model.impl;

import com.intellij.lang.javascript.flex.FlexModuleType;
import com.intellij.lang.javascript.flex.library.FlexLibraryType;
import com.intellij.lang.javascript.flex.projectStructure.FlexIdeBCConfigurator;
import com.intellij.lang.javascript.flex.projectStructure.FlexSdk;
import com.intellij.lang.javascript.flex.projectStructure.FlexSdkLibraryType;
import com.intellij.lang.javascript.flex.projectStructure.model.*;
import com.intellij.lang.javascript.flex.projectStructure.options.BCUtils;
import com.intellij.lang.javascript.flex.projectStructure.options.BuildConfigurationNature;
import com.intellij.lang.javascript.flex.projectStructure.options.FlexProjectRootsUtil;
import com.intellij.lang.javascript.flex.projectStructure.ui.FlexIdeBCConfigurable;
import com.intellij.lang.javascript.flex.projectStructure.ui.FlexSdkPanel;
import com.intellij.openapi.Disposable;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.module.Module;
import com.intellij.openapi.module.ModuleType;
import com.intellij.openapi.options.ConfigurationException;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.roots.LibraryOrderEntry;
import com.intellij.openapi.roots.ModifiableRootModel;
import com.intellij.openapi.roots.ModuleOrderEntry;
import com.intellij.openapi.roots.OrderEntry;
import com.intellij.openapi.roots.impl.libraries.LibraryEx;
import com.intellij.openapi.roots.impl.libraries.LibraryTableBase;
import com.intellij.openapi.roots.libraries.Library;
import com.intellij.openapi.roots.libraries.LibraryTable;
import com.intellij.openapi.roots.libraries.LibraryTablesRegistrar;
import com.intellij.openapi.roots.ui.configuration.libraryEditor.LibraryEditor;
import com.intellij.openapi.util.Condition;
import com.intellij.util.Function;
import com.intellij.util.Processor;
import com.intellij.util.containers.*;
import com.intellij.util.containers.HashMap;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import javax.swing.event.ChangeListener;
import java.util.*;

/**
 * User: ksafonov
 */
public class FlexProjectConfigurationEditor implements Disposable {

  private static final Logger LOG = Logger.getInstance(FlexProjectConfigurationEditor.class.getName());

  private static class Editor extends FlexIdeBuildConfigurationImpl {
    private final Module myModule;
    private final FlexIdeBuildConfigurationImpl myOrigin;

    Editor(FlexIdeBuildConfigurationImpl origin, Module module) {
      myOrigin = origin;
      myModule = module;
      origin.applyTo(this);
    }

    public FlexIdeBuildConfigurationImpl commit() {
      applyTo(myOrigin);
      return myOrigin;
    }

    public boolean isModified() {
      return !isEqual(myOrigin);
    }
  }

  public interface ProjectModifiableModelProvider {
    Module[] getModules();

    ModifiableRootModel getModuleModifiableModel(Module module);

    void addListener(FlexIdeBCConfigurator.Listener listener, Disposable parentDisposable);

    void commitModifiableModels() throws ConfigurationException;

    LibraryTableBase.ModifiableModelEx getGlobalLibrariesModifiableModel();
  }

  private boolean myDisposed;
  private final ProjectModifiableModelProvider myProvider;
  private final Project myProject;

  private final Map<Module, List<Editor>> myModule2Editors = new HashMap<Module, List<Editor>>();
  private final FlexSdksEditor mySdksEditor;

  public FlexProjectConfigurationEditor(Project project, ProjectModifiableModelProvider provider) {
    myProject = project;
    myProvider = provider;
    mySdksEditor = new FlexSdksEditor(project, myProvider.getGlobalLibrariesModifiableModel());

    provider.addListener(new FlexIdeBCConfigurator.Listener() {
      @Override
      public void moduleRemoved(final Module module) {
        if (!isFlex(module)) {
          return;
        }

        LOG.assertTrue(myModule2Editors.containsKey(module), "Unknown module: " + module);
        myModule2Editors.remove(module);
        //Condition<OrderEntry> c = new Condition<OrderEntry>() {
        //  @Override
        //  public boolean value(OrderEntry orderEntry) {
        //    return orderEntry instanceof ModuleOrderEntry && ((ModuleOrderEntry)orderEntry).getModule() == module;
        //  }
        //};
        //for (Module m : myModule2Editors.keySet()) {
        //  ModifiableRootModel modifiableModel = myProvider.getModuleModifiableModel(m);
        //  List<OrderEntry> orderEntriesToRemove = ContainerUtil.findAll(modifiableModel.getOrderEntries(), c);
        //  for (OrderEntry orderEntry : orderEntriesToRemove) {
        //    modifiableModel.removeOrderEntry(orderEntry);
        //  }
        //}
      }

      @Override
      public void buildConfigurationRemoved(FlexIdeBCConfigurable configurable) {
        // TODO
      }
    }, this);

    for (Module module : provider.getModules()) {
      if (!isFlex(module)) {
        continue;
      }
      addEditorsForModule(module);
    }
  }

  private void addEditorsForModule(Module module) {
    FlexIdeBuildConfiguration[] buildConfigurations = FlexBuildConfigurationManager.getInstance(module).getBuildConfigurations();
    List<Editor> configEditors = new ArrayList<Editor>(buildConfigurations.length);
    for (FlexIdeBuildConfiguration buildConfiguration : buildConfigurations) {
      configEditors.add(new Editor((FlexIdeBuildConfigurationImpl)buildConfiguration, module));
    }
    myModule2Editors.put(module, configEditors);
  }

  @Override
  public void dispose() {
    myDisposed = true;
    myModule2Editors.clear();
  }

  public ModifiableFlexIdeBuildConfiguration[] getConfigurations(Module module) {
    assertAlive();
    List<Editor> editors = myModule2Editors.get(module);
    if (editors == null) {
      // module was just created
      addEditorsForModule(module);
      editors = myModule2Editors.get(module);
    }
    return editors.toArray(new ModifiableFlexIdeBuildConfiguration[editors.size()]);
  }

  private void assertAlive() {
    LOG.assertTrue(!myDisposed, "Already disposed");
  }

  public ModifiableFlexIdeBuildConfiguration createConfiguration(Module module) {
    assertAlive();
    List<Editor> editors = myModule2Editors.get(module);
    Editor newConfig = new Editor(new FlexIdeBuildConfigurationImpl(), module);
    editors.add(newConfig);
    return newConfig;
  }

  public ModifiableFlexIdeBuildConfiguration copyConfiguration(ModifiableFlexIdeBuildConfiguration configuration,
                                                               BuildConfigurationNature newNature) {
    assertAlive();
    Module module = ((Editor)configuration).myModule;
    List<Editor> editors = myModule2Editors.get(module);
    FlexIdeBuildConfigurationImpl copy = ((Editor)configuration).getCopy();
    Editor newConfig = new Editor(copy, module);
    newConfig.setNature(newNature);
    // just to simplify serialized view
    resetNonApplicableValuesToDefaults(newConfig);
    editors.add(newConfig);
    return newConfig;
  }

  private static void resetNonApplicableValuesToDefaults(final ModifiableFlexIdeBuildConfiguration configuration) {
    final FlexIdeBuildConfiguration defaultConfiguration = new FlexIdeBuildConfigurationImpl();

    if (configuration.getOutputType() != OutputType.RuntimeLoadedModule) {
      configuration.setOptimizeFor(defaultConfiguration.getOptimizeFor());
    }

    if (configuration.getOutputType() == OutputType.Library) {
      configuration.setMainClass(defaultConfiguration.getMainClass());
    }

    if (configuration.getTargetPlatform() != TargetPlatform.Web ||
        configuration.getOutputType() != OutputType.Application) {
      configuration.setUseHtmlWrapper(defaultConfiguration.isUseHtmlWrapper());
      configuration.setWrapperTemplatePath(defaultConfiguration.getWrapperTemplatePath());
    }

    if (configuration.getTargetPlatform() != TargetPlatform.Web) {
      configuration.getDependencies().setTargetPlayer(defaultConfiguration.getDependencies().getTargetPlayer());
    }

    if (configuration.getTargetPlatform() == TargetPlatform.Mobile || configuration.isPureAs()) {
      configuration.getDependencies().setComponentSet(defaultConfiguration.getDependencies().getComponentSet());
    }

    BuildConfigurationNature nature = configuration.getNature();
    for (Iterator<ModifiableDependencyEntry> i = configuration.getDependencies().getModifiableEntries().iterator(); i.hasNext(); ) {
      if (!BCUtils.isApplicable(nature, i.next().getDependencyType().getLinkageType())) {
        i.remove();
      }
    }
  }

  public Module getModule(ModifiableFlexIdeBuildConfiguration configuration) {
    assertAlive();
    return ((Editor)configuration).myModule;
  }

  public Project getProject() {
    return myProject;
  }

  public void checkCanCommit() {
    //To change body of created methods use File | Settings | File Templates.
  }

  public void commit() throws ConfigurationException {
    for (Module module : myModule2Editors.keySet()) {
      ModifiableRootModel modifiableModel = myProvider.getModuleModifiableModel(module);

      // ---------------- SDK entries ----------------------
      Map<LibraryEx, Boolean> sdksToAdd = new HashMap<LibraryEx, Boolean>(); // Library -> add_library_entry_flag
      for (Editor editor : myModule2Editors.get(module)) {
        SdkEntry sdkEntry = editor.getDependencies().getSdkEntry();
        if (sdkEntry != null) {
          LibraryEx sdkLibrary = ((SdkEntryImpl)sdkEntry).findLibrary(myProvider.getGlobalLibrariesModifiableModel().getLibraries());
          if (sdkLibrary != null) {
            sdksToAdd.put(sdkLibrary, true);
          }
        }
      }

      Collection<OrderEntry> sdkEntriesToRemove = new ArrayList<OrderEntry>();
      for (OrderEntry orderEntry : modifiableModel.getOrderEntries()) {
        if (orderEntry instanceof LibraryOrderEntry) {
          if (LibraryTablesRegistrar.APPLICATION_LEVEL.equals(((LibraryOrderEntry)orderEntry).getLibraryLevel())) {
            LibraryEx sdkLibrary = (LibraryEx)((LibraryOrderEntry)orderEntry).getLibrary();
            if (sdksToAdd.containsKey(sdkLibrary)) {
              sdksToAdd.put(sdkLibrary, false);
            }
            else if (sdkLibrary != null && sdkLibrary.getType() instanceof FlexSdkLibraryType) {
              sdkEntriesToRemove.add(orderEntry);
            }
          }
        }
      }

      for (OrderEntry e : sdkEntriesToRemove) {
        modifiableModel.removeOrderEntry(e);
      }

      for (LibraryEx library : sdksToAdd.keySet()) {
        if (!library.isDisposed() && sdksToAdd.get(library) && myProvider.getGlobalLibrariesModifiableModel().getLibraryByName(library.getName()) != null) {
          modifiableModel.addLibraryEntry(library);
        }
      }

      // ---------------- modules entries ----------------------
      final Map<Module, Boolean> modulesToAdd = new HashMap<Module, Boolean>(); // Library -> add_module_entry_flag
      for (Editor editor : myModule2Editors.get(module)) {
        for (DependencyEntry dependencyEntry : editor.getDependencies().getEntries()) {
          if (dependencyEntry instanceof BuildConfigurationEntry) {
            Editor bc = findBc((BuildConfigurationEntry)dependencyEntry);
            if (bc != null) {
              modulesToAdd.put(bc.myModule, true);
            }
          }
        }
      }

      List<OrderEntry> moduleOrderEntriesToRemove = ContainerUtil.filter(modifiableModel.getOrderEntries(), new Condition<OrderEntry>() {
        @Override
        public boolean value(OrderEntry orderEntry) {
          if (orderEntry instanceof ModuleOrderEntry) {
            Module m = ((ModuleOrderEntry)orderEntry).getModule();
            if (modulesToAdd.containsKey(m)) {
              modulesToAdd.put(m, false);
              return false;
            }
            else {
              return true;
            }
          }
          return false;
        }
      });

      for (OrderEntry orderEntry : moduleOrderEntriesToRemove) {
        modifiableModel.removeOrderEntry(orderEntry);
      }
      for (Module m : modulesToAdd.keySet()) {
        if (modulesToAdd.get(m)) {
          modifiableModel.addModuleOrderEntry(m);
        }
      }
    }

    // ---------------- do commit ----------------------
    boolean commitModifiableModel = !ContainerUtil.process(myModule2Editors.keySet(), new Processor<Module>() {
      @Override
      public boolean process(Module module) {
        return !myProvider.getModuleModifiableModel(module).isChanged();
      }
    });

    if (commitModifiableModel) {
      myProvider.commitModifiableModels();
    }

    ApplicationManager.getApplication().runWriteAction(new Runnable() {
      public void run() {
        for (Module module : myModule2Editors.keySet()) {
          Function<Editor, FlexIdeBuildConfiguration> f = new Function<Editor, FlexIdeBuildConfiguration>() {
            @Override
            public FlexIdeBuildConfiguration fun(Editor editor) {
              return editor.commit();
            }
          };
          FlexIdeBuildConfiguration[] current = ContainerUtil.map2Array(myModule2Editors.get(module), FlexIdeBuildConfiguration.class, f);
          FlexBuildConfigurationManager.getInstance(module).setBuildConfigurations(current);
        }

        if (mySdksEditor.isModified()) {
          mySdksEditor.commit();
        }
      }
    });
  }

  public void removeConfiguration(ModifiableFlexIdeBuildConfiguration configuration) {
    assertAlive();
    Editor editor = (Editor)configuration;
    List<Editor> editors = myModule2Editors.get(editor.myModule);
    boolean contained = editors.remove(editor);
    LOG.assertTrue(contained);
  }

  public boolean isModified() {
    if (myDisposed) {
      return false;
    }

    for (Module module : myModule2Editors.keySet()) {
      if (myProvider.getModuleModifiableModel(module).isChanged()) {
        return true;
      }

      FlexIdeBuildConfiguration[] originalConfigurations = FlexBuildConfigurationManager.getInstance(module).getBuildConfigurations();
      List<Editor> currentConfigurations = myModule2Editors.get(module);
      if (originalConfigurations.length != currentConfigurations.size()) {
        return true;
      }
      for (Editor currentConfiguration : currentConfigurations) {
        if (currentConfiguration.isModified()) {
          return true;
        }
      }
    }

    if (mySdksEditor.isModified()) {
      return true;
    }
    return false;
  }

  private static boolean isFlex(Module module) {
    return ModuleType.get(module) == FlexModuleType.getInstance();
  }

  public ModifiableBuildConfigurationEntry createBcEntry(ModifiableDependencies dependant, ModifiableFlexIdeBuildConfiguration dependency) {
    assertAlive();
    Module dependencyModule = ((Editor)dependency).myModule;
    ModifiableBuildConfigurationEntry e = new BuildConfigurationEntryImpl(dependencyModule, dependency.getName());

    //Module dependantModule = getEditor(dependant).myModule;
    //if (dependantModule != dependencyModule) {
    //  ModifiableRootModel modifiableModel = myProvider.getModuleModifiableModel(dependantModule);
    //  if (!ArrayUtil.contains(dependencyModule, modifiableModel.getModuleDependencies())) {
    //    modifiableModel.addModuleOrderEntry(dependencyModule);
    //  }
    //}
    return e;
  }

  public ModifiableBuildConfigurationEntry createBcEntry(ModifiableDependencies dependant, String moduleName, String bcName) {
    assertAlive();
    Module dependantModule = getEditor(dependant).myModule;
    return new BuildConfigurationEntryImpl(dependantModule.getProject(), moduleName, bcName);
  }

  public ModifiableModuleLibraryEntry createModuleLibraryEntry(ModifiableDependencies dependant, String dependencyLibraryId) {
    assertAlive();
    ModuleLibraryEntryImpl e = new ModuleLibraryEntryImpl(dependencyLibraryId);

    Module dependantModule = getEditor(dependant).myModule;
    ModifiableRootModel modifiableModel = myProvider.getModuleModifiableModel(dependantModule);
    LOG.assertTrue(findLibrary(modifiableModel, dependencyLibraryId) != null);
    return e;
  }

  @Nullable
  private static Library findLibrary(ModifiableRootModel modifiableModel, String libraryId) {
    for (Library library : modifiableModel.getModuleLibraryTable().getLibraries()) {
      if (((LibraryEx)library).getType() instanceof FlexLibraryType) { // allow subclasses
        if (libraryId.equals(FlexProjectRootsUtil.getLibraryId(library))) {
          return library;
        }
      }
    }
    return null;
  }

  public void setEntries(ModifiableDependencies dependant, List<? extends ModifiableDependencyEntry> newEntries) {
    assertAlive();

    Map<String, ModifiableDependencyEntry> moduleLibrariesEntries = new HashMap<String, ModifiableDependencyEntry>();
    for (ModifiableDependencyEntry entry : dependant.getModifiableEntries()) {
      if (entry instanceof ModuleLibraryEntry) {
        moduleLibrariesEntries.put(((ModuleLibraryEntry)entry).getLibraryId(), entry);
      }
    }

    List<ModifiableDependencyEntry> entriesToRemove = new ArrayList<ModifiableDependencyEntry>(dependant.getModifiableEntries());
    for (Iterator<? extends ModifiableDependencyEntry> i = newEntries.iterator(); i.hasNext(); ) {
      ModifiableDependencyEntry entry = i.next();
      if (entry instanceof ModuleLibraryEntry) {
        ModifiableDependencyEntry existingEntry = moduleLibrariesEntries.get(((ModuleLibraryEntry)entry).getLibraryId());
        if (existingEntry != null) {
          entriesToRemove.remove(existingEntry);
          existingEntry.getDependencyType().copyFrom(entry.getDependencyType());
          i.remove();
        }
      }
    }

    Editor dependantEditor = getEditor(dependant);
    ModifiableRootModel dependantModifiableModel = myProvider.getModuleModifiableModel(dependantEditor.myModule);

    for (DependencyEntry entry : entriesToRemove) {
      if (entry instanceof ModuleLibraryEntry) {
        ModuleLibraryEntry libraryEntry = (ModuleLibraryEntry)entry;
        Library dependencyLibrary = findLibrary(dependantModifiableModel, libraryEntry.getLibraryId());
        if (dependencyLibrary != null) {
          LibraryOrderEntry orderEntry = dependantModifiableModel.findLibraryOrderEntry(dependencyLibrary);
          LOG.assertTrue(orderEntry != null);
          // TODO should we explicitly delete library?
          dependantModifiableModel.removeOrderEntry(orderEntry);
        }
      }
    }
    dependant.getModifiableEntries().removeAll(entriesToRemove);
    dependant.getModifiableEntries().addAll(newEntries);
  }


  @Nullable
  private Editor findBc(final BuildConfigurationEntry bcEntry) {
    Module dependencyModule = ContainerUtil.find(myModule2Editors.keySet(), new Condition<Module>() {
      @Override
      public boolean value(Module module) {
        return bcEntry.getModuleName().equals(module.getName());
      }
    });

    if (dependencyModule == null) {
      return null;
    }

    return ContainerUtil.find(myModule2Editors.get(dependencyModule), new Condition<Editor>() {
      @Override
      public boolean value(Editor editor) {
        return editor.getName().equals(bcEntry.getBcName());
      }
    });
  }

  @Nullable
  public LibraryOrderEntry findLibraryOrderEntry(ModifiableDependencies dependencies, ModuleLibraryEntry moduleLibraryEntry) {
    assertAlive();
    ModifiableRootModel modifiableModel = myProvider.getModuleModifiableModel(getEditor(dependencies).myModule);
    Library library = findLibrary(modifiableModel, moduleLibraryEntry.getLibraryId());
    return library != null ? modifiableModel.findLibraryOrderEntry(library) : null;
  }

  public LibraryOrderEntry findLibraryOrderEntry(ModifiableDependencies dependencies, Library library) {
    ModifiableRootModel modifiableModel = myProvider.getModuleModifiableModel(getEditor(dependencies).myModule);
    return modifiableModel.findLibraryOrderEntry(library);
  }

  private Editor getEditor(ModifiableDependencies dependencies) {
    for (List<Editor> editors : myModule2Editors.values()) {
      for (Editor editor : editors) {
        if (editor.getDependencies() == dependencies) {
          return editor;
        }
      }
    }
    throw new IllegalArgumentException("unknown dependencies instance");
  }

  public Module getModule(ModifiableDependencies dependencies) {
    assertAlive();
    return getEditor(dependencies).myModule;
  }

  public LibraryTableBase.ModifiableModelEx getLibraryModel(ModifiableDependencies dependencies) {
    assertAlive();
    ModifiableRootModel modifiableModel = myProvider.getModuleModifiableModel(getEditor(dependencies).myModule);
    return (LibraryTableBase.ModifiableModelEx)modifiableModel.getModuleLibraryTable().getModifiableModel();
  }

  public Library[] getSdksLibraries() {
    return mySdksEditor.getLibraries();
  }

  @Nullable
  public FlexSdk findSdk(String libraryId) {
    return mySdksEditor.findSdk(libraryId);
  }

  public void addSdkListListener(ChangeListener changeListener, Disposable parentDisposable) {
    mySdksEditor.addSdkListListener(changeListener, parentDisposable);
  }

  @NotNull
  public FlexSdk findOrCreateSdk(@NotNull String homePath) {
    return mySdksEditor.findOrCreateSdk(homePath);
  }

  public void setSdkLibraryUsed(Object user, @Nullable Library sdk) {
    mySdksEditor.setUsed(user, sdk);
  }

  public LibraryEditor getSdkLibraryEditor(Library library) {
    return mySdksEditor.getLibraryEditor(library);
  }
}


