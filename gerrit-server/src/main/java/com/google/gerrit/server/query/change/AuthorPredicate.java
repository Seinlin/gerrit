// Copyright (C) 2015 The Android Open Source Project
//
// Licensed under the Apache License, Version 2.0 (the "License");
// you may not use this file except in compliance with the License.
// You may obtain a copy of the License at
//
// http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing, software
// distributed under the License is distributed on an "AS IS" BASIS,
// WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
// See the License for the specific language governing permissions and
// limitations under the License.

package com.google.gerrit.server.query.change;

import static com.google.gerrit.server.index.ChangeField.AUTHOR;
import static com.google.gerrit.server.query.change.ChangeQueryBuilder.FIELD_AUTHOR;

import com.google.gerrit.server.index.ChangeField;
import com.google.gerrit.server.index.IndexPredicate;
import com.google.gwtorm.server.OrmException;

public class AuthorPredicate extends IndexPredicate<ChangeData>  {
  AuthorPredicate(String value) {
    super(AUTHOR, FIELD_AUTHOR, value);
  }

  @Override
  public boolean match(ChangeData object) throws OrmException {
    return ChangeField.getAuthorParts(object).contains(
        getValue().toLowerCase());
  }

  @Override
  public int getCost() {
    return 1;
  }
}
