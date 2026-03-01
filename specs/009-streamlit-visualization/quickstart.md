# Quickstart: Streamlit Visualization

**Feature Branch**: `009-streamlit-visualization`
**Date**: 2026-02-28

## Prerequisites

- Platform deployed with features 001-008
- A running analysis workspace (feature 002)
- Sample data provisioned (feature 004)

## Scenario 1: View Sample Streamlit Dashboard

1. Navigate to **Analyses** in the portal
2. Open an existing analysis (e.g., "California Housing ML")
3. Ensure the workspace is running (Notebooks tab shows JupyterLab)
4. Click the **Visualization** tab
5. The sample dashboard (`visualize/sample_dashboard.py`) should auto-detect and load
6. Interact with the dashboard — adjust sliders, select features, observe chart updates

**Expected result**: Interactive Streamlit dashboard embedded in the tab showing California Housing data visualizations.

## Scenario 2: No Streamlit Files (Onboarding Guide)

1. Create a new analysis
2. Launch the workspace
3. In the Notebooks tab, delete or rename the `visualize/` folder
4. Switch to the **Visualization** tab
5. Verify the onboarding guide is displayed with:
   - Explanation of Streamlit visualization support
   - The `visualize/` folder convention
   - Quick-start code snippet
   - Instructions to create a Streamlit file

**Expected result**: Guide message displayed with actionable instructions.

## Scenario 3: Create a Custom Streamlit App

1. In the Notebooks tab (JupyterLab), create a new file `visualize/my_dashboard.py`
2. Add Streamlit code:
   ```python
   import streamlit as st
   import pandas as pd

   st.title("My Custom Dashboard")
   data = pd.DataFrame({"x": range(10), "y": [i**2 for i in range(10)]})
   st.line_chart(data.set_index("x"))
   ```
3. Save the file
4. Switch to the **Visualization** tab
5. If the sample file also exists, verify a dropdown appears with both files
6. Select `my_dashboard.py` from the dropdown
7. Verify the custom dashboard loads

**Expected result**: Custom Streamlit app loads in the Visualization tab. Dropdown shows both files when multiple exist.

## Scenario 4: Switch Between Multiple Apps

1. Ensure at least two Streamlit files exist in `visualize/`
2. Open the **Visualization** tab
3. Note the dropdown selector showing available files
4. The first file loads by default
5. Select a different file from the dropdown
6. Verify the new app replaces the previous one (loading indicator during transition)

**Expected result**: Seamless switching between Streamlit apps via dropdown.

## Scenario 5: Workspace Not Running

1. Navigate to an analysis where the workspace is stopped/terminated
2. Click the **Visualization** tab
3. Verify a message indicating the workspace must be running
4. Verify a button/link to navigate to the Notebooks tab to start the workspace

**Expected result**: Clear message with actionable navigation to start the workspace.

## Scenario 6: Tab Preservation (Process Stays Alive)

1. Open the **Visualization** tab with a running Streamlit app
2. Interact with widgets (e.g., set a slider to a specific value)
3. Switch to the **Notebooks** tab
4. Switch back to the **Visualization** tab
5. Verify the Streamlit app is in the same state (slider value preserved)

**Expected result**: Instant return to the Streamlit app with state preserved.

## Verification Checklist

- [ ] Visualization tab appears after Experiments tab
- [ ] Sample Streamlit file exists in `visualize/` of new workspaces
- [ ] Sample dashboard loads and is interactive
- [ ] Onboarding guide shows when no Streamlit files exist
- [ ] Dropdown appears with multiple files, hidden with single file
- [ ] File switching stops previous app and starts new one
- [ ] Workspace-not-running state shows appropriate message
- [ ] Tab switching preserves Streamlit state (iframe kept in DOM)
- [ ] Loading indicator shown during Streamlit startup
- [ ] Timeout message after 60 seconds with retry option
